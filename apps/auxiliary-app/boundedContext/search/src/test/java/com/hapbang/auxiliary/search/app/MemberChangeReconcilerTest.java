package com.hapbang.auxiliary.search.app;

import static com.hapbang.auxiliary.search.support.FakeMemberStatsSource.category;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.app.MemberChangeReconciler.ReconcileResult;
import com.hapbang.auxiliary.search.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.out.InfluencerIndexSetup;
import com.hapbang.auxiliary.search.out.SyncCursor;
import com.hapbang.auxiliary.search.support.FakeMemberChangeSource;
import com.hapbang.auxiliary.search.support.FakeMemberStatsSource;
import com.hapbang.auxiliary.search.support.FakeMemberChangeSource.Request;
import com.hapbang.auxiliary.search.support.InMemorySyncCursorStore;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/** 증분 보정(#772). 회원 앱·커서는 가짜, ES는 nori 컨테이너다. 이벤트 반영은 실제 {@link InfluencerMemberIndexer}로 흉내 낸다 */
class MemberChangeReconcilerTest {

    /** 지금 시각(KST) */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 20);
    /** 지난 회차가 저장한 커서 */
    private static final LocalDateTime SAVED = LocalDateTime.of(2026, 11, 10, 10, 15);
    private static final String CURSOR = SyncCursor.MEMBER_CHANGES;

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final Clock clock = Clock.fixed(NOW.atZone(MemberChangeReconciler.ZONE).toInstant(),
            MemberChangeReconciler.ZONE);
    private final InfluencerIndexSetup indexSetup = new InfluencerIndexSetup(client);
    private final InfluencerDocumentStore store = new InfluencerDocumentStore(client);
    private final InfluencerMemberIndexer indexer = new InfluencerMemberIndexer(indexSetup, store,
            new ContentCategoryNames(new FakeMemberStatsSource()
                    .categories(category("GAME", "게임"), category("MAPLESTORY", "메이플스토리"))), clock);
    private final FakeMemberChangeSource source = new FakeMemberChangeSource();
    private final InMemorySyncCursorStore cursors = new InMemorySyncCursorStore();
    private final MemberChangeReconciler reconciler = new MemberChangeReconciler(indexSetup, store, indexer, source,
            cursors, null, clock);

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
        indexSetup.ensureReady();
    }

    @Test
    @DisplayName("커서가 없으면 지금 시각부터 읽고 그 커서를 저장한다. 이전 변경은 읽지 않는다(처음 적재 #773 몫)")
    void firstRunStartsNow() throws Exception {
        source.change(NOW.minusHours(1), influencer(1L, 3L, List.of("GAME")));

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.read()).isZero();
        assertThat(source.requests()).containsExactly(new Request(NOW.minusMinutes(1), 0L));
        assertThat(cursors.find(CURSOR)).contains(new SyncCursor(CURSOR, NOW, 0L, NOW));
        assertThat(exists(1L)).isFalse();
    }

    @Test
    @DisplayName("이벤트 없이 분야를 바꾸면 다음 보정에서 문서가 바뀐다(덮음)")
    void missedCategoryChange() throws Exception {
        indexer.apply(influencer(1L, 4L, List.of("GAME")));
        cursors.moveTo(CURSOR, SAVED, 9L);
        source.change(SAVED.plusMinutes(2), influencer(1L, 5L, List.of("GAME", "MAPLESTORY")));

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.count(ReconcileKind.OVERWRITTEN)).isEqualTo(1);
        assertThat(result.fixed()).isEqualTo(1);
        assertThat(source(1L)).containsEntry("memberVersion", 5).containsEntry("contentCategories",
                List.of(Map.of("code", "GAME", "name", "게임"), Map.of("code", "MAPLESTORY", "name", "메이플스토리")));
        assertThat(cursors.find(CURSOR)).contains(new SyncCursor(CURSOR, SAVED.plusMinutes(2), 1L, NOW));
    }

    @Test
    @DisplayName("강등 이벤트를 놓쳐도 다음 보정에서 숨긴다(tombstone, 개인정보 지움)")
    void missedDemotion() throws Exception {
        indexer.apply(influencer(1L, 4L, List.of("GAME")));
        cursors.moveTo(CURSOR, SAVED, 9L);
        source.change(SAVED.plusMinutes(2), new UserDto(1L, "메이플장인", Role.USER, UserStatus.ACTIVE, 5L));

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.count(ReconcileKind.HIDDEN)).isEqualTo(1);
        assertThat(source(1L)).containsEntry("hidden", true).containsEntry("memberVersion", 5)
                .doesNotContainKeys("nickname", "bio");
    }

    @Test
    @DisplayName("승급 이벤트를 놓쳐 문서가 없으면 넣는다")
    void missedPromotion() throws Exception {
        cursors.moveTo(CURSOR, SAVED, 9L);
        source.change(SAVED.plusMinutes(2), influencer(1L, 2L, List.of("GAME")));

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.count(ReconcileKind.INSERTED)).isEqualTo(1);
        assertThat(source(1L)).containsEntry("hidden", false).containsEntry("nickname", "메이플장인");
    }

    @Test
    @DisplayName("늦게 커밋된 변경(커서보다 이른 updatedAt)도 1분 겹쳐 읽기로 반영한다. 1분보다 이른 변경은 읽지 않는다")
    void lateCommitWithinOverlap() throws Exception {
        cursors.moveTo(CURSOR, SAVED, 9L);
        source.change(SAVED.minusSeconds(20), influencer(3L, 1L, List.of("GAME")))
                .change(SAVED.minusSeconds(61), influencer(4L, 1L, List.of("GAME")));

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(source.requests()).containsExactly(new Request(SAVED.minusMinutes(1), 0L));
        assertThat(result.count(ReconcileKind.INSERTED)).isEqualTo(1);
        assertThat(exists(3L)).isTrue();
        assertThat(exists(4L)).isFalse();
    }

    @Test
    @DisplayName("같은 변경을 이벤트와 보정이 둘 다 반영해도 결과가 같다(이미 반영, 보정 0건)")
    void eventAndReconcileSameResult() throws Exception {
        UserDto changed = influencer(1L, 5L, List.of("GAME", "MAPLESTORY"));
        indexer.apply(changed);
        Map<String, Object> afterEvent = source(1L);
        cursors.moveTo(CURSOR, SAVED, 9L);
        source.change(SAVED.plusMinutes(2), changed);

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.count(ReconcileKind.ALREADY_APPLIED)).isEqualTo(1);
        assertThat(result.fixed()).isZero();
        assertThat(source(1L)).isEqualTo(afterEvent);
    }

    @Test
    @DisplayName("보이는 내용은 같고 version만 오르면(프로필 사진 변경) 보정으로 세지 않는다. version은 올린다")
    void versionOnly() throws Exception {
        indexer.apply(influencer(1L, 4L, List.of("GAME")));
        cursors.moveTo(CURSOR, SAVED, 9L);
        source.change(SAVED.plusMinutes(2), influencer(1L, 5L, List.of("GAME")));

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.count(ReconcileKind.VERSION_ONLY)).isEqualTo(1);
        assertThat(result.fixed()).isZero();
        assertThat(source(1L)).containsEntry("memberVersion", 5);
    }

    @Test
    @DisplayName("일반 유저의 변경은 숨김 문서만 남기고 보정으로 세지 않는다(검색에 보이는 것이 그대로)")
    void plainUserIsNotFix() throws Exception {
        cursors.moveTo(CURSOR, SAVED, 9L);
        source.change(SAVED.plusMinutes(2), new UserDto(2L, "일반", Role.USER, UserStatus.ACTIVE, 3L));

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.count(ReconcileKind.VERSION_ONLY)).isEqualTo(1);
        assertThat(source(2L)).containsEntry("hidden", true);
    }

    @Test
    @DisplayName("중간에 멈추면 커서는 마지막으로 끝낸 페이지에 있고, 다시 돌면 거기서 1분 겹쳐 이어 간다")
    void resumesFromSavedCursor() throws Exception {
        cursors.moveTo(CURSOR, SAVED, 9L);
        source.pageSize(2).failOnCall(2)
                .change(SAVED.plusMinutes(1), influencer(1L, 1L, List.of("GAME")))
                .change(SAVED.plusMinutes(2), influencer(2L, 1L, List.of("GAME")))
                .change(SAVED.plusMinutes(3), influencer(3L, 1L, List.of("GAME")));

        assertThatThrownBy(() -> reconciler.reconcile(null)).isInstanceOf(IllegalStateException.class);
        assertThat(cursors.find(CURSOR)).contains(new SyncCursor(CURSOR, SAVED.plusMinutes(2), 2L, null));
        assertThat(exists(3L)).isFalse();

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(source.requests().get(2)).isEqualTo(new Request(SAVED.plusMinutes(1), 0L));
        assertThat(result.count(ReconcileKind.INSERTED)).isEqualTo(1);
        assertThat(result.count(ReconcileKind.ALREADY_APPLIED)).isEqualTo(2);
        assertThat(exists(3L)).isTrue();
        assertThat(cursors.find(CURSOR)).contains(new SyncCursor(CURSOR, SAVED.plusMinutes(3), 3L, NOW));
    }

    @Test
    @DisplayName("바깥에서 시작 시각을 정하면(#773 따라잡기) 커서를 그 시각으로 옮기고 겹쳐 읽기 없이 거기서부터 읽는다")
    void reconcileFromGivenTime() throws Exception {
        cursors.moveTo(CURSOR, SAVED, 9L);
        LocalDateTime from = SAVED.minusHours(1);
        source.change(from.plusMinutes(30), influencer(1L, 1L, List.of("GAME")));

        ReconcileResult result = reconciler.reconcile(from);

        assertThat(source.requests()).containsExactly(new Request(from, 0L));
        assertThat(result.count(ReconcileKind.INSERTED)).isEqualTo(1);
        assertThat(cursors.find(CURSOR)).contains(new SyncCursor(CURSOR, from.plusMinutes(30), 1L, NOW));
    }

    @Test
    @DisplayName("회원 정보가 빠진 항목은 건너뛰고 나머지를 계속 반영한다")
    void skipsBrokenMember() throws Exception {
        cursors.moveTo(CURSOR, SAVED, 9L);
        source.change(SAVED.plusMinutes(1), new UserDto(1L, "역할없음", null, UserStatus.ACTIVE, 1L))
                .change(SAVED.plusMinutes(2), influencer(2L, 1L, List.of("GAME")));

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.count(ReconcileKind.SKIPPED)).isEqualTo(1);
        assertThat(result.count(ReconcileKind.INSERTED)).isEqualTo(1);
        assertThat(cursors.find(CURSOR).orElseThrow().memberId()).isEqualTo(2L);
    }

    private static UserDto influencer(long id, long version, List<String> categories) {
        return new UserDto(id, "메이플장인", Role.INFLUENCER, UserStatus.ACTIVE, version, categories, "소개");
    }

    private boolean exists(long userId) throws Exception {
        return client.exists(e -> e.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId))).value();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long userId) throws Exception {
        return client.get(g -> g.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(userId)), Map.class)
                .source();
    }
}
