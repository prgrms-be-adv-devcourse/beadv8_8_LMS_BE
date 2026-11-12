package com.hapbang.auxiliary.search.influencer.app;

import static com.hapbang.auxiliary.search.support.FakeMemberStatsSource.category;
import static com.hapbang.auxiliary.search.support.FakeMemberStatsSource.channel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.search.common.app.SearchJob;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.common.out.IndexDefinition;
import com.hapbang.auxiliary.search.common.app.Reindexer.ReindexReason;
import com.hapbang.auxiliary.search.common.app.Reindexer.ReindexResult;
import com.hapbang.auxiliary.search.common.app.IndexCatchUp.CatchUp;
import com.hapbang.auxiliary.search.common.app.Reindexer;
import com.hapbang.auxiliary.search.influencer.config.SearchRankingProperties;
import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.influencer.out.InfluencerSearcher;
import com.hapbang.auxiliary.search.influencer.out.MemberDocument;
import com.hapbang.auxiliary.search.influencer.out.SyncCursor;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.support.FakeMemberChangeSource;
import com.hapbang.auxiliary.search.support.FakeMemberListSource;
import com.hapbang.auxiliary.search.support.FakeMemberStatsSource;
import com.hapbang.auxiliary.search.support.InMemoryConsistencyCheckStore;
import com.hapbang.auxiliary.search.support.InMemorySyncCursorStore;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.auxiliary.search.support.SharedAdvisoryLocks;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 처음 적재와 무중단 재색인(#773 완료 조건). nori ES 컨테이너로 돈다. 회원 앱은 가짜, 락은 pod 사이에서 공유하는 메모리 락이다.
 */
class InfluencerReindexerTest {

    /** 지금 시각(KST). 재색인의 시각 T다 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 20);
    /** 지금 설정과 다른 옛 index(사용자 사전·mapping이 바뀌기 전) */
    private static final String OLD = InfluencerIndexDefinition.INDEX_PREFIX + "old000000000";

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final Clock clock = Clock.fixed(NOW.atZone(MemberChangeReconciler.ZONE).toInstant(),
            MemberChangeReconciler.ZONE);
    private final String expected = InfluencerIndexDefinition.load().indexName();
    private final SharedAdvisoryLocks locks = new SharedAdvisoryLocks();
    private final FakeMemberListSource members = new FakeMemberListSource();
    private final FakeMemberStatsSource stats = new FakeMemberStatsSource()
            .categories(category("GAME", "게임"), category("MAPLESTORY", "메이플스토리"));
    private final FakeMemberChangeSource changes = new FakeMemberChangeSource();
    private final InMemorySyncCursorStore cursors = new InMemorySyncCursorStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** 이 테스트의 pod 하나 */
    private Pod pod;

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
        pod = new Pod(new InfluencerDocumentStore(client), new InfluencerIndexSetup(client), runner(), runner());
    }

    @Test
    @DisplayName("alias가 없으면 정상 인플루언서만 새 index에 넣고 통계를 채운 뒤 alias를 붙이고, T − 1분부터 따라잡는다")
    void firstLoad() throws Exception {
        members.pageSize(2)
                .user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 3, "GAME"))
                .user(user(2, Role.USER, UserStatus.ACTIVE, 1))
                .user(user(3, Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 1, "MAPLESTORY"))
                .user(user(4, Role.INFLUENCER, UserStatus.SUSPENDED, 2, "GAME"));
        stats.influencer(1, 4.5, 2, channel("롤방송", 1000)).influencer(3, null, 0);

        ReindexResult result = pod.reindexer.reindexIfNeeded();

        assertThat(result.reason()).isEqualTo(ReindexReason.FIRST_LOAD);
        assertThat(result.loaded().read()).isEqualTo(4);
        assertThat(result.loaded().visible()).isEqualTo(2);
        assertThat(result.loaded().hidden()).isEqualTo(2);
        assertThat(result.catchUp()).isEqualTo(CatchUp.CAUGHT_UP);
        assertThat(aliasTargets()).containsExactly(expected);
        assertThat(source(1)).containsEntry("nickname", "닉네임1").containsEntry("memberVersion", 3)
                .containsEntry("followerCount", 1000).containsEntry("representativeChannelName", "롤방송");
        assertThat(source(3)).containsEntry("verified", true);
        // 정상 인플루언서가 아닌 회원은 숨김 문서(version만, 개인정보 없음)로 넣어 늦게 온 옛 이벤트를 막는다(#773 리뷰 Q3)
        assertThat(source(2)).containsEntry("hidden", true).containsEntry("memberVersion", 1).doesNotContainKey("nickname");
        assertThat(source(4)).containsEntry("hidden", true).containsEntry("memberVersion", 2);
        assertThat(cursors.find(SyncCursor.MEMBER_CHANGES).orElseThrow().memberUpdatedAt())
                .isEqualTo(NOW.minusMinutes(1));
        assertThat(registry.get("hapbang.search.reindex.documents").tag("reason", "first_load").counter().count())
                .isEqualTo(2);
        assertThat(registry.get("hapbang.search.reindex.duration").tag("reason", "first_load")
                .tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("설정 해시가 같으면 재색인하지 않는다(alias 조회만). 동의어만 바뀐 경우도 해시가 같다(InfluencerIndexDefinitionTest)")
    void upToDateDoesNothing() throws Exception {
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));
        pod.reindexer.reindexIfNeeded();
        int calls = members.calls();

        ReindexResult again = pod.reindexer.reindexIfNeeded();

        assertThat(again.done()).isFalse();
        assertThat(members.calls()).isEqualTo(calls);
        assertThat(aliasTargets()).containsExactly(expected);
    }

    @Test
    @DisplayName("사용자 사전·mapping이 바뀌면 새 index로 옮기고 옛 index와 실패하고 남은 index를 지운다")
    void settingsChanged() throws Exception {
        createOldIndex();
        String stray = InfluencerIndexDefinition.INDEX_PREFIX + "stray0000000";
        client.indices().create(c -> c.index(stray));
        pod.store.show(1, 1L, profile("옛닉네임"));
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 3, "GAME"));

        ReindexResult result = pod.reindexer.reindexIfNeeded();

        assertThat(result.reason()).isEqualTo(ReindexReason.SETTINGS_CHANGED);
        assertThat(aliasTargets()).containsExactly(expected);
        assertThat(indices()).containsExactly(expected);
        assertThat(source(1)).containsEntry("nickname", "닉네임1").containsEntry("memberVersion", 3);
    }

    @Test
    @DisplayName("재색인 중에 바뀐 회원(분야 수정, 강등)은 이벤트가 옛 index로 가도, alias 교체 뒤 따라잡기로 새 index에 반영된다")
    void catchesUpChangesDuringReindex() throws Exception {
        createOldIndex();
        UserDto before1 = user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME");
        UserDto before2 = user(2, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME");
        pod.indexer.apply(before1);
        pod.indexer.apply(before2);
        UserDto after1 = user(1, Role.INFLUENCER, UserStatus.ACTIVE, 2, "MAPLESTORY");
        UserDto after2 = user(2, Role.USER, UserStatus.ACTIVE, 2, "GAME");
        AtomicBoolean changed = new AtomicBoolean();
        members.user(before1).user(before2).onPage(() -> {
            // 회원 목록을 읽은 직후 두 회원이 바뀌었다. 이벤트는 alias(옛 index)로 가고, 변경분 API에는 T 뒤 시각으로 보인다
            if (changed.compareAndSet(false, true)) {
                pod.indexer.apply(after1);
                pod.indexer.apply(after2);
                changes.change(NOW.plusSeconds(5), after1).change(NOW.plusSeconds(6), after2);
            }
        });

        pod.reindexer.reindexIfNeeded();

        assertThat(aliasTargets()).containsExactly(expected);
        assertThat(source(1)).containsEntry("memberVersion", 2);
        assertThat(categoryCodes(1)).containsExactly("MAPLESTORY");
        assertThat(source(2)).containsEntry("hidden", true).containsEntry("memberVersion", 2);
    }

    @Test
    @DisplayName("재색인하는 동안 계속 검색해도 실패가 없고, 늘 전체 인플루언서가 나온다(alias 원자 교체)")
    void searchNeverFailsDuringReindex() throws Exception {
        createOldIndex();
        for (long id = 1; id <= 3; id++) {
            UserDto user = user(id, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME");
            pod.indexer.apply(user);
            members.user(user);
        }
        client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));
        // 한 명씩 천천히 읽어 재색인이 검색과 충분히 겹치게 한다
        members.pageSize(1).onPage(() -> sleep(150));
        InfluencerSearcher searcher = new InfluencerSearcher(client, new SearchRankingProperties(null, null, null, null));
        InfluencerSearchCondition all = new InfluencerSearchCondition(null, List.of(), null, null, null,
                InfluencerSort.DEFAULT, 0, 20);
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger searches = new AtomicInteger();
        List<Object> problems = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread searching = Thread.ofVirtual().start(() -> {
            while (!done.get()) {
                try {
                    long total = searcher.search(all, 3.0, 1_000).total();
                    if (total != 3) {
                        problems.add("total=" + total);
                    }
                } catch (RuntimeException e) {
                    problems.add(e);
                }
                searches.incrementAndGet();
            }
        });

        pod.reindexer.reindexIfNeeded();
        // 교체한 뒤에도 몇 번 더 찾는다
        int afterSwitch = searches.get();
        while (searches.get() < afterSwitch + 5) {
            sleep(10);
        }
        done.set(true);
        searching.join();

        assertThat(aliasTargets()).containsExactly(expected);
        assertThat(searches.get()).isGreaterThan(10);
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("원본 수와 새 index 문서 수가 다르면 alias를 붙이지 않고 새 index를 지운다(처음 적재)")
    void countMismatchKeepsAlias() throws Exception {
        // 회원 앱이 같은 회원을 두 번 줬다: 넣은 수 2, 문서 수 1
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"))
                .user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));

        assertThat(pod.reindexer.reindexIfNeeded().failed()).isTrue();

        assertThat(aliasTargets()).isEmpty();
        assertThat(indices()).isEmpty();
        assertThat(cursors.find(SyncCursor.MEMBER_CHANGES)).isEmpty();
        assertThat(registry.get("hapbang.search.reindex.duration").tag("reason", "first_load")
                .tag("outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("bulk가 실패하면 alias를 옛 index 그대로 두고 새 index를 지운다. 다음 확인 때 다시 하면 옮긴다")
    void bulkFailureKeepsAlias() throws Exception {
        createOldIndex();
        AtomicBoolean fail = new AtomicBoolean(true);
        InfluencerDocumentStore failing = new InfluencerDocumentStore(client) {
            @Override
            public Map<Long, WriteOutcome> loadMembers(String index, List<MemberDocument> documents) {
                return fail.get() ? Map.of(1L, WriteOutcome.FAILED) : super.loadMembers(index, documents);
            }
        };
        Pod failingPod = new Pod(failing, new InfluencerIndexSetup(client), runner(), runner());
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));

        assertThat(failingPod.reindexer.reindexIfNeeded().failed()).isTrue();
        assertThat(aliasTargets()).containsExactly(OLD);
        assertThat(indices()).containsExactly(OLD);

        fail.set(false);
        failingPod.reindexer.reindexIfNeeded();
        assertThat(aliasTargets()).containsExactly(expected);
        assertThat(indices()).containsExactly(expected);
    }

    @Test
    @DisplayName("새 index를 만들지 못하면(사용자 사전·mapping 오류) alias를 그대로 두고 실패 결과와 실패 지표를 남긴다(PR #845 리뷰)")
    void createIndexFailureKeepsAlias() throws Exception {
        createOldIndex();
        InfluencerIndexSetup rejecting = new InfluencerIndexSetup(client) {
            @Override
            public String createIndex() {
                throw new IllegalStateException("ES가 index 설정을 거절했다");
            }
        };
        Pod rejectingPod = new Pod(new InfluencerDocumentStore(client), rejecting, runner(), runner());
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));

        ReindexResult result = rejectingPod.reindexer.reindexIfNeeded();

        assertThat(result.failed()).isTrue();
        assertThat(result.reason()).isEqualTo(ReindexReason.SETTINGS_CHANGED);
        assertThat(aliasTargets()).containsExactly(OLD);
        assertThat(members.calls()).isZero();
        assertThat(registry.get("hapbang.search.reindex.duration").tag("reason", "settings_changed")
                .tag("outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("두 pod가 동시에 시작해도 한 번만 돈다. 늦은 pod는 락을 잡지 못해 건너뛴다")
    void twoPodsRunOnce() throws Exception {
        Pod other = new Pod(new InfluencerDocumentStore(client), new InfluencerIndexSetup(client), runner(), runner());
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch otherTried = new CountDownLatch(1);
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME")).onPage(() -> {
            loading.countDown();
            await(otherTried);
        });

        Thread first = Thread.ofVirtual().start(pod.reindexer::run);
        await(loading);
        other.reindexer.run();
        otherTried.countDown();
        first.join(Duration.ofSeconds(30));

        assertThat(members.calls()).isEqualTo(1);
        assertThat(aliasTargets()).containsExactly(expected);
        assertThat(registry.get("hapbang.search.reindex.duration").timers()).hasSize(1);
        assertThat(registry.get("hapbang.search.reindex.duration").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("alias를 옮긴 뒤 정기 보정이 락을 쥐고 있으면 기다렸다 다시 불러 따라잡는다. 끝내 못 하면 catch_up_failed로 남긴다")
    void catchUpWaitsForBusyReconcile() throws Exception {
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));
        AtomicInteger busy = new AtomicInteger(2);
        Pod waiting = new Pod(new InfluencerDocumentStore(client), new InfluencerIndexSetup(client), runner(),
                busyRunner(busy));

        assertThat(waiting.reindexer.reindexIfNeeded().catchUp()).isEqualTo(CatchUp.CAUGHT_UP);
        assertThat(busy.get()).isEqualTo(-1);

        SearchElasticsearch.reset(client);
        busy.set(Integer.MAX_VALUE);
        assertThat(waiting.reindexer.reindexIfNeeded().catchUp()).isEqualTo(CatchUp.BUSY);
        assertThat(aliasTargets()).containsExactly(expected);
        assertThat(registry.get("hapbang.search.reindex.duration").tag("outcome", "catch_up_failed").timer().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ES가 그 회원 값 때문에 거절한 회원은 건너뛰고(수 비교에서 빠짐) 나머지로 처음 적재를 끝낸다")
    void skipsRejectedMember() throws Exception {
        InfluencerDocumentStore rejecting = new InfluencerDocumentStore(client) {
            @Override
            public Map<Long, WriteOutcome> loadMembers(String index, List<MemberDocument> documents) {
                Map<Long, WriteOutcome> outcomes = new java.util.HashMap<>(super.loadMembers(index,
                        documents.stream().filter(d -> d.userId() != 2L).toList()));
                outcomes.put(2L, WriteOutcome.REJECTED);
                return outcomes;
            }
        };
        Pod rejectingPod = new Pod(rejecting, new InfluencerIndexSetup(client), runner(), runner());
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"))
                .user(user(2, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));

        ReindexResult result = rejectingPod.reindexer.reindexIfNeeded();

        assertThat(result.done()).isTrue();
        assertThat(result.loaded().visible()).isEqualTo(1);
        assertThat(result.loaded().skipped()).isEqualTo(1);
        assertThat(aliasTargets()).containsExactly(expected);
    }

    @Test
    @DisplayName("alias 교체 응답만 실패하고 ES에는 적용됐으면(시간 초과) 새 index를 지우지 않고 교체한 것으로 보고 이어 간다")
    void switchTimeoutAfterApplied() throws Exception {
        createOldIndex();
        InfluencerIndexSetup timingOut = new InfluencerIndexSetup(client) {
            @Override
            public void switchAlias(java.util.Collection<String> from, String to) {
                super.switchAlias(from, to);
                throw new IllegalStateException("응답 시간 초과");
            }
        };
        Pod timingOutPod = new Pod(new InfluencerDocumentStore(client), timingOut, runner(), runner());
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));

        ReindexResult result = timingOutPod.reindexer.reindexIfNeeded();

        assertThat(result.done()).isTrue();
        assertThat(result.catchUp()).isEqualTo(CatchUp.CAUGHT_UP);
        assertThat(aliasTargets()).containsExactly(expected);
        assertThat(indices()).containsExactly(expected);
    }

    @Test
    @DisplayName("pod가 떠 있는 동안 ES를 새로 만들면 이벤트는 건너뛰고(DLT 아님), 다음 확인이 동의어부터 다시 맞춰 처음 적재한다")
    void elasticsearchRecreatedWhilePodRuns() throws Exception {
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));
        pod.reindexer.reindexIfNeeded();
        pod.indexer.apply(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 2, "GAME"));

        SearchElasticsearch.reset(client);

        assertThatThrownBy(() -> pod.indexer.apply(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 3, "GAME")))
                .isInstanceOf(com.hapbang.auxiliary.search.common.out.IndexNotReadyException.class);
        ReindexResult result = pod.reindexer.reindexIfNeeded();
        assertThat(result.reason()).isEqualTo(ReindexReason.FIRST_LOAD);
        assertThat(client.synonyms().getSynonym(g -> g.id(IndexDefinition.SYNONYMS_SET).size(10_000)).synonymsSet())
                .hasSize(11);
        assertThat(source(1)).containsEntry("hidden", false);
    }

    @Test
    @DisplayName("롤링 배포 중 옛 pod는 자기가 뜬 뒤에 만들어진(더 새 배포의) index를 옛 설정으로 되돌리지 않는다. 롤백(나중에 뜬 pod)은 되돌린다")
    void oldPodSkipsNewerIndex() throws Exception {
        // 시계 차이 여유(1분)보다 앞서 뜬 옛 pod
        Instant oldPodStartedAt = Instant.now().minus(Reindexer.CLOCK_SKEW_MARGIN).minusSeconds(60);
        // 새 배포가 다른 설정(OLD를 새 설정 index로 본다)으로 alias를 옮겼다
        createOldIndex();
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));
        Pod oldPod = new Pod(new InfluencerDocumentStore(client), new InfluencerIndexSetup(client), runner(), runner(),
                oldPodStartedAt);

        assertThat(oldPod.reindexer.reindexIfNeeded().done()).isFalse();
        assertThat(aliasTargets()).containsExactly(OLD);
        assertThat(members.calls()).isZero();

        // 롤백: 새 index보다 나중에 뜬 pod는 자기 설정으로 옮긴다
        assertThat(pod.reindexer.reindexIfNeeded().done()).isTrue();
        assertThat(aliasTargets()).containsExactly(expected);
    }

    @Test
    @DisplayName("index가 pod보다 늦게 만들어졌어도 시계 차이 여유(1분) 안이면 더 새 배포로 보지 않고 재색인한다(PR #845 재리뷰)")
    void clockSkewWithinMarginStillReindexes() throws Exception {
        Instant podStartedAt = Instant.now().minusSeconds(10);
        createOldIndex();
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));
        Pod justStarted = new Pod(new InfluencerDocumentStore(client), new InfluencerIndexSetup(client), runner(),
                runner(), podStartedAt);

        assertThat(justStarted.reindexer.reindexIfNeeded().done()).isTrue();
        assertThat(aliasTargets()).containsExactly(expected);
    }

    @Test
    @DisplayName("ES가 동의어 묶음을 거절하면 재색인하지 않는다(PR #845 재리뷰). 거절 지표는 대상마다 남기지 않는다(SynonymsCheck가 한 번, #895)")
    void synonymsRejectedStopsReindex() throws Exception {
        InfluencerIndexSetup rejecting = new InfluencerIndexSetup(client) {
            @Override
            public void resyncSynonyms() {
                throw new com.hapbang.auxiliary.search.common.out.SynonymsRejectedException("규칙이 틀렸다", null);
            }
        };
        Pod rejectingPod = new Pod(new InfluencerDocumentStore(client), rejecting, runner(), runner());
        members.user(user(1, Role.INFLUENCER, UserStatus.ACTIVE, 1, "GAME"));

        assertThat(rejectingPod.reindexer.reindexIfNeeded().failed()).isTrue();
        assertThat(aliasTargets()).isEmpty();
        assertThat(members.calls()).isZero();
        assertThat(registry.find("hapbang.search.synonyms.rejected").counter()).isNull();
    }

    /** 검색 모듈 빈 묶음 하나(pod 하나) */
    private final class Pod {

        private final InfluencerDocumentStore store;
        private final InfluencerMemberIndexer indexer;
        private final InfluencerReindexer reindexer;

        Pod(InfluencerDocumentStore store, InfluencerIndexSetup indexSetup, SearchJobRunner reindexRunner,
                SearchJobRunner reconcileRunner) {
            // 이 테스트의 pod는 지금 있는 어떤 index보다 나중에 뜬 새 배포다(옛 pod는 oldPodSkipsNewerIndex에서 따로)
            this(store, indexSetup, reindexRunner, reconcileRunner, Instant.now().plus(Duration.ofHours(1)));
        }

        Pod(InfluencerDocumentStore store, InfluencerIndexSetup indexSetup, SearchJobRunner reindexRunner,
                SearchJobRunner reconcileRunner, Instant startedAt) {
            ContentCategoryNames categoryNames = new ContentCategoryNames(stats);
            InMemoryConsistencyCheckStore consistencyChecks = new InMemoryConsistencyCheckStore();
            SearchSyncMetrics metrics = new SearchSyncMetrics(registry, cursors, consistencyChecks, clock);
            this.store = store;
            this.indexer = new InfluencerMemberIndexer(indexSetup, store, categoryNames, clock);
            MemberChangeReconciler reconciler = new MemberChangeReconciler(indexSetup, store, indexer, changes, cursors,
                    reconcileRunner, metrics, clock);
            InfluencerStatsSync statsSync = new InfluencerStatsSync(indexSetup, store, stats, categoryNames,
                    reindexRunner, consistencyChecks, clock);
            this.reindexer = new InfluencerReindexer(indexSetup, store, indexer, categoryNames, members, statsSync,
                    reconciler, reindexRunner, registry, clock, Duration.ofMillis(10), Duration.ofMillis(200), startedAt);
        }
    }

    /** 공유 락을 쓰는 실제 실행기(pod 하나의 락 연결) */
    private SearchJobRunner runner() {
        return new SearchJobRunner(new JobLock(locks.session(),
                new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class)));
    }

    /** 증분 보정 락이 busy번 동안 다른 회차에 잡혀 있는 실행기 */
    private SearchJobRunner busyRunner(AtomicInteger busy) {
        SearchJobRunner real = runner();
        return new SearchJobRunner(null) {
            @Override
            public boolean run(SearchJob job, Runnable task) {
                if (job == SearchJob.MEMBER_RECONCILE && busy.getAndDecrement() > 0) {
                    return false;
                }
                return real.run(job, task);
            }
        };
    }

    private void createOldIndex() throws Exception {
        String body = InfluencerIndexDefinition.load().indexBody();
        client.indices().create(c -> c.index(OLD).withJson(new StringReader(body))
                .aliases(InfluencerIndexDefinition.ALIAS, a -> a.isWriteIndex(true)));
    }

    private java.util.Set<String> aliasTargets() {
        return new InfluencerIndexSetup(client).state().aliasTargets();
    }

    private java.util.Set<String> indices() {
        return new InfluencerIndexSetup(client).state().indices();
    }

    private static UserDto user(long id, Role role, UserStatus status, long version, String... categories) {
        return new UserDto(id, "닉네임" + id, role, status, version, List.of(categories), "자기소개" + id);
    }

    private static com.hapbang.auxiliary.search.influencer.out.MemberProfile profile(String nickname) {
        return new com.hapbang.auxiliary.search.influencer.out.MemberProfile(nickname, null, "INFLUENCER", false, List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long userId) throws Exception {
        return client.get(g -> g.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId)), Map.class).source();
    }

    @SuppressWarnings("unchecked")
    private List<String> categoryCodes(long userId) throws Exception {
        return ((List<Map<String, Object>>) source(userId).get("contentCategories")).stream()
                .map(category -> (String) category.get("code")).toList();
    }

    private boolean exists(long userId) throws Exception {
        return client.exists(e -> e.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId))).value();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
