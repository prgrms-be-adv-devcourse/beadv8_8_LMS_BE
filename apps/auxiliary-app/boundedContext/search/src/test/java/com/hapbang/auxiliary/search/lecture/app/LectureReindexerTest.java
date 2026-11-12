package com.hapbang.auxiliary.search.lecture.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.search.common.app.IndexCatchUp.CatchUp;
import com.hapbang.auxiliary.search.common.app.Reindexer.ReindexReason;
import com.hapbang.auxiliary.search.common.app.Reindexer.ReindexResult;
import com.hapbang.auxiliary.search.common.app.SearchJobRunner;
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.support.FakeLectureSyncSource;
import com.hapbang.auxiliary.search.support.FakeMemberListSource;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.auxiliary.search.support.SharedAdvisoryLocks;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.RepositoryTest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 강의 index 처음 적재·재색인(#884 완료 조건): 사본 맞추기 → 강의 적재 → alias 교체 → 따라잡기, 적재 중 바뀐 강의·리뷰·판매자가 교체 뒤
 * 반영된다. 공통 흐름(실패 시 alias 유지, 교체 시간 초과 등)은 {@code ReindexerTest}가 본다. 회원·강의 앱은 가짜, 사본은 PostgreSQL 컨테이너,
 * 문서는 nori ES 컨테이너다
 */
@RepositoryTest
@Import({SearchMemberCopyStore.class, LectureReviewCopyStore.class})
class LectureReindexerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T06:00:00Z"), SellerIndexer.ZONE);
    private static final long SELLER = 7L;
    private static final long SUSPENDED_SELLER = 8L;

    @Autowired
    private SearchMemberCopyStore members;

    @Autowired
    private LectureReviewCopyStore reviews;

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final LectureIndexSetup indexSetup = new LectureIndexSetup(client);
    private final String expected = LectureIndexDefinition.load().indexName();
    private final SharedAdvisoryLocks locks = new SharedAdvisoryLocks();
    private final FakeMemberListSource memberList = new FakeMemberListSource();
    private final FakeLectureSyncSource source = new FakeLectureSyncSource();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
    }

    @Test
    @DisplayName("alias가 없으면 사본을 맞춘 뒤(일부만 차 있어도) 모든 강의를 넣고 alias를 붙인다. 판매자·평점은 사본 값, 판매 중이 아닌 강의는 숨김")
    void firstLoad() throws Exception {
        // 운영처럼 사본이 일부만 차 있다(#882 배포 뒤 이벤트로 들어온 회원 하나). 비었는지와 상관없이 모두 맞춘다
        members.apply(new UserDto(99L, "다른회원", Role.USER, UserStatus.ACTIVE, 1L), LocalDateTime.now(CLOCK));
        memberList.user(new UserDto(SELLER, "편집장", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 2L))
                .user(new UserDto(SUSPENDED_SELLER, "정지강사", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED, 5L));
        source.pageSize(2)
                .lecture(lecture(11L, SELLER, "프리미어 컷편집", true, 1L))
                .lecture(lecture(12L, SELLER, "검수 대기", false, 0L))
                .lecture(lecture(13L, SUSPENDED_SELLER, "정지 판매자 강의", true, 0L))
                .review(review(1L, 11L, 5, 0L)).review(review(2L, 11L, 4, 0L));

        ReindexResult result = reindexer(runner()).reindexIfNeeded();

        assertThat(result.reason()).isEqualTo(ReindexReason.FIRST_LOAD);
        assertThat(result.loaded().read()).isEqualTo(3);
        assertThat(result.loaded().visible()).isEqualTo(2);
        assertThat(result.loaded().hidden()).isEqualTo(1);
        assertThat(result.catchUp()).isEqualTo(CatchUp.CAUGHT_UP);
        assertThat(indexSetup.state().aliasTargets()).containsExactly(expected);
        assertThat(source(11L)).containsEntry("title", "프리미어 컷편집").containsEntry("hidden", false)
                .containsEntry("sellerNickname", "편집장").containsEntry("sellerVersion", 2)
                .containsEntry("averageRating", 4.5).containsEntry("reviewCount", 2);
        assertThat(source(12L)).containsEntry("hidden", true);
        assertThat(source(13L)).containsEntry("sellerActive", false).containsEntry("sellerNickname", "정지강사");
        assertThat(registry.get("hapbang.search.reindex.documents").tag("target", "lecture")
                .tag("reason", "first_load").counter().count()).isEqualTo(2);
        assertThat(registry.get("hapbang.search.reindex.duration").tag("target", "lecture")
                .tag("reason", "first_load").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("적재 중에 바뀐 강의·리뷰·판매자(이벤트는 사본만 고치고 문서 쓰기는 건너뜀)가 alias 교체 뒤 따라잡기로 반영된다")
    void changesDuringLoadAreCaughtUp() throws Exception {
        memberList.user(new UserDto(SELLER, "편집장", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 1L));
        AtomicInteger calls = new AtomicInteger();
        source.pageSize(1)
                .lecture(lecture(11L, SELLER, "처음 제목", true, 0L))
                .lecture(lecture(12L, SELLER, "다른 강의", true, 0L))
                .review(review(1L, 11L, 5, 0L))
                // 강의 11을 넣은 뒤(두 번째 페이지를 줄 때) 원본이 바뀌고 이벤트가 사본만 고친다(처음 적재 전이라 문서 쓰기는 건너뜀)
                .onLecturePage(() -> {
                    if (calls.incrementAndGet() == 2) {
                        source.lecture(lecture(11L, SELLER, "수정한 제목", true, 1L));
                        reviews.apply(review(2L, 11L, 1, 0L), LocalDateTime.now(CLOCK));
                        members.apply(new UserDto(SELLER, "새닉네임", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED, 2L),
                                LocalDateTime.now(CLOCK));
                    }
                });

        ReindexResult result = reindexer(runner()).reindexIfNeeded();

        assertThat(result.catchUp()).isEqualTo(CatchUp.CAUGHT_UP);
        assertThat(source(11L)).containsEntry("title", "수정한 제목").containsEntry("lectureVersion", 1)
                .containsEntry("averageRating", 3.0).containsEntry("reviewCount", 2)
                .containsEntry("sellerNickname", "새닉네임").containsEntry("sellerActive", false);
        assertThat(source(12L)).containsEntry("sellerActive", false);
    }

    @Test
    @DisplayName("설정 해시가 같으면 다시 적재하지 않는다(강의·회원 앱을 부르지 않음)")
    void upToDateDoesNothing() {
        source.lecture(lecture(11L, SELLER, "제목", true, 0L));
        LectureReindexer reindexer = reindexer(runner());
        reindexer.reindexIfNeeded();
        int lectureCalls = source.lectureCalls();
        int memberCalls = memberList.calls();

        assertThat(reindexer.reindexIfNeeded().done()).isFalse();
        assertThat(source.lectureCalls()).isEqualTo(lectureCalls);
        assertThat(memberList.calls()).isEqualTo(memberCalls);
    }

    @Test
    @DisplayName("강의를 넣지 못하면(장애) alias를 붙이지 않고 새 index를 지운다. 다음 확인에 처음부터 다시 한다")
    void failureKeepsNoAlias() {
        source.lecture(lecture(11L, SELLER, "제목", true, 0L));
        LectureDocumentStore failing = new LectureDocumentStore(client) {
            @Override
            public Map<Long, WriteOutcome> loadLectures(String index, List<LectureLoad> lectures, Instant now) {
                return Map.of(11L, WriteOutcome.FAILED);
            }
        };

        ReindexResult result = reindexer(failing, runner()).reindexIfNeeded();

        assertThat(result.failed()).isTrue();
        assertThat(indexSetup.state().aliasTargets()).isEmpty();
        assertThat(indexSetup.state().indices()).isEmpty();
        assertThat(registry.get("hapbang.search.reindex.duration").tag("target", "lecture")
                .tag("outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("ES가 그 강의 값 때문에 거절한 강의는 건너뛰고(수 비교에서 빠짐) 나머지로 처음 적재를 끝낸다")
    void skipsRejectedLecture() {
        source.lecture(lecture(11L, SELLER, "제목", true, 0L)).lecture(lecture(12L, SELLER, "거절", true, 0L));
        LectureDocumentStore rejecting = new LectureDocumentStore(client) {
            @Override
            public Map<Long, WriteOutcome> loadLectures(String index, List<LectureLoad> lectures, Instant now) {
                Map<Long, WriteOutcome> outcomes = new HashMap<>(super.loadLectures(index,
                        lectures.stream().filter(l -> l.lecture().id() != 12L).toList(), now));
                outcomes.put(12L, WriteOutcome.REJECTED);
                return outcomes;
            }
        };

        ReindexResult result = reindexer(rejecting, runner()).reindexIfNeeded();

        assertThat(result.done()).isTrue();
        assertThat(result.loaded().visible()).isEqualTo(1);
        assertThat(result.loaded().skipped()).isEqualTo(1);
        assertThat(indexSetup.state().aliasTargets()).containsExactly(expected);
    }

    private LectureReindexer reindexer(SearchJobRunner runner) {
        return reindexer(new LectureDocumentStore(client), runner);
    }

    /** 검색 모듈의 강의 빈 묶음 하나(pod 하나). 이 pod는 지금 있는 어떤 index보다 나중에 뜬 새 배포다 */
    private LectureReindexer reindexer(LectureDocumentStore documents, SearchJobRunner runner) {
        LectureSyncMetrics metrics = new LectureSyncMetrics(registry);
        LectureIndexer indexer = new LectureIndexer(indexSetup, documents, members, reviews, CLOCK);
        LectureReconciler reconciler = new LectureReconciler(indexSetup, documents, indexer, source, runner, metrics,
                CLOCK);
        LectureCopyAligner aligner = new LectureCopyAligner(indexSetup, documents, members, reviews);
        LectureReviewReconciler reviewReconciler = new LectureReviewReconciler(reviews, source, aligner, runner,
                metrics, CLOCK);
        return new LectureReindexer(indexSetup, documents, members, reviews, memberList, source, reconciler,
                reviewReconciler, aligner, runner, registry, CLOCK, Duration.ofMillis(10), Duration.ofMillis(200),
                Instant.now().plus(Duration.ofHours(1)));
    }

    /** 공유 락을 쓰는 실제 실행기(pod 하나의 락 연결) */
    private SearchJobRunner runner() {
        return new SearchJobRunner(new JobLock(locks.session(),
                new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class)));
    }

    private static LectureSyncDto lecture(long id, long sellerUserId, String title, boolean onSale, long version) {
        return new LectureSyncDto(id, sellerUserId, title, "소개", 10_000L, onSale, false,
                List.of(new LectureContentSyncDto(1L, "1강", 1)), version);
    }

    private static LectureReviewSyncDto review(long id, long lectureId, int rating, long version) {
        return new LectureReviewSyncDto(id, lectureId, 9L, rating, true, false, version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long id) throws Exception {
        return client.get(g -> g.index(LectureIndexDefinition.ALIAS).id(String.valueOf(id)), Map.class).source();
    }
}
