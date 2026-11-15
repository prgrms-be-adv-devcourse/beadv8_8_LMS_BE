package com.hapbang.auxiliary.search.lecture.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.auxiliary.search.out.SyncCursor;
import com.hapbang.auxiliary.search.support.FakeMemberChangeSource;
import com.hapbang.auxiliary.search.support.FakeMemberChangeSource.Request;
import com.hapbang.auxiliary.search.support.InMemorySyncCursorStore;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.RepositoryTest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 판매자 사본 증분 보정(#884): 놓친 닉네임·상태 변경이 다음 보정에서 회원 사본과 강의 문서에 반영된다. 회원 앱·커서는 가짜, 사본은 PostgreSQL
 * 컨테이너, 문서는 nori ES 컨테이너다
 */
@RepositoryTest
@Import({SearchMemberCopyStore.class, LectureReviewCopyStore.class})
class SellerChangeReconcilerTest {

    /** 지금 시각(KST) */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 15, 20);
    /** 지난 회차가 저장한 커서 */
    private static final LocalDateTime SAVED = LocalDateTime.of(2026, 11, 12, 15, 10);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(SellerIndexer.ZONE).toInstant(), SellerIndexer.ZONE);
    private static final String CURSOR = SyncCursor.SELLER_CHANGES;
    private static final long SELLER = 7L;

    @Autowired
    private SearchMemberCopyStore members;

    @Autowired
    private LectureReviewCopyStore reviews;

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final LectureIndexSetup indexSetup = new LectureIndexSetup(client);
    private final LectureDocumentStore documents = new LectureDocumentStore(client);
    private final FakeMemberChangeSource source = new FakeMemberChangeSource();
    private final InMemorySyncCursorStore cursors = new InMemorySyncCursorStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private SellerIndexer sellerIndexer;
    private SellerChangeReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
        sellerIndexer = new SellerIndexer(indexSetup, documents, members, CLOCK);
        reconciler = new SellerChangeReconciler(sellerIndexer, members, source, cursors, null,
                new LectureSyncMetrics(registry), CLOCK);
        new LectureIndexer(indexSetup, documents, members, reviews, CLOCK)
                .apply(new LectureSyncDto(11L, SELLER, "강의", "소개", 10_000L, true, false, List.of(), 0L));
    }

    @Test
    @DisplayName("커서가 없으면 지금 시각부터 읽고 그 커서를 저장한다. 이전 회원은 처음 적재의 사본 맞추기 몫이다")
    void firstRunStartsNow() {
        source.change(NOW.minusHours(1), seller("편집장", UserStatus.ACTIVE, 1L));

        assertThat(reconciler.reconcile().read()).isZero();
        assertThat(source.requests()).containsExactly(new Request(NOW.minusMinutes(1), 0L));
        assertThat(cursors.find(CURSOR)).contains(new SyncCursor(CURSOR, NOW, 0L, NOW));
        assertThat(members.findSeller(SELLER)).isEmpty();
    }

    @Test
    @DisplayName("정지 이벤트를 놓친 판매자는 다음 보정에서 회원 사본과 그 강의 문서를 고친다")
    void missedSuspension() throws Exception {
        sellerIndexer.apply(seller("편집장", UserStatus.ACTIVE, 1L));
        cursors.moveTo(CURSOR, SAVED, 3L);
        source.change(SAVED.plusMinutes(2), seller("편집장", UserStatus.SUSPENDED, 2L));

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.SELLER_FIXED)).isEqualTo(1);
        assertThat(members.findSeller(SELLER)).contains(new Seller(SELLER, "편집장", false, 2L));
        assertThat(source()).containsEntry("sellerActive", false).containsEntry("sellerVersion", 2);
        assertThat(cursors.find(CURSOR)).contains(new SyncCursor(CURSOR, SAVED.plusMinutes(2), SELLER, NOW));
        assertThat(registry.get("hapbang.search.lecture.reconcile.items").tag("job", "seller")
                .tag("kind", "seller_fixed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("이벤트가 이미 반영한 회원(겹쳐 읽음 포함)은 다시 반영하지 않고, 판매자가 아닌 회원은 사본만 바뀐다")
    void alreadyAppliedAndNonSeller() {
        sellerIndexer.apply(seller("편집장", UserStatus.ACTIVE, 2L));
        cursors.moveTo(CURSOR, SAVED, 3L);
        source.change(SAVED.minusSeconds(30), seller("편집장", UserStatus.ACTIVE, 2L))
                .change(SAVED.plusMinutes(1), new UserDto(30L, "일반회원", Role.USER, UserStatus.ACTIVE, 1L));

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.ALREADY_APPLIED)).isEqualTo(1);
        assertThat(result.count(LectureReconcileKind.COPIED)).isEqualTo(1);
        assertThat(result.count(LectureReconcileKind.SELLER_FIXED)).isZero();
        assertThat(members.findSeller(30L)).contains(new Seller(30L, "일반회원", true, 1L));
    }

    @Test
    @DisplayName("alias가 없으면(처음 적재 전) 사본만 고치고 다음 회원을 계속한다")
    void notReady() throws Exception {
        SearchElasticsearch.reset(client);
        cursors.moveTo(CURSOR, SAVED, 3L);
        source.change(SAVED.plusMinutes(1), seller("편집장", UserStatus.ACTIVE, 1L))
                .change(SAVED.plusMinutes(2), new UserDto(8L, "다른강사", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 1L));

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.COPIED)).isEqualTo(2);
        assertThat(members.findSeller(SELLER)).contains(new Seller(SELLER, "편집장", true, 1L));
        assertThat(members.findSeller(8L)).isPresent();
    }

    @Test
    @DisplayName("사본이 이미 같거나 더 새 version이면 반영하지 않는다. version 없는 정보는 가릴 수 없어 반영한다")
    void alreadyCopied() {
        Seller copy = new Seller(SELLER, "편집장", true, 2L);

        assertThat(SellerChangeReconciler.alreadyCopied(seller("편집장", UserStatus.ACTIVE, 2L), copy)).isTrue();
        assertThat(SellerChangeReconciler.alreadyCopied(seller("편집장", UserStatus.ACTIVE, 1L), copy)).isTrue();
        assertThat(SellerChangeReconciler.alreadyCopied(seller("편집장", UserStatus.ACTIVE, 3L), copy)).isFalse();
        assertThat(SellerChangeReconciler.alreadyCopied(seller("편집장", UserStatus.ACTIVE, null), copy)).isFalse();
        assertThat(SellerChangeReconciler.alreadyCopied(seller("편집장", UserStatus.ACTIVE, 2L), null)).isFalse();
    }

    private static UserDto seller(String nickname, UserStatus status, Long version) {
        return new UserDto(SELLER, nickname, Role.VERIFIED_INFLUENCER, status, version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source() throws Exception {
        return client.get(g -> g.index(LectureIndexDefinition.ALIAS).id("11"), Map.class).source();
    }
}
