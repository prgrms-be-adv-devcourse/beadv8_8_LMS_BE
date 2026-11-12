package com.hapbang.auxiliary.search.lecture.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;
import com.hapbang.auxiliary.search.lecture.out.LectureRating;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.auxiliary.search.support.FakeLectureSyncSource;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.RepositoryTest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 리뷰 보정과 판매자·평점 칸 맞추기(#884 L13, Q1 a): 놓친 리뷰 이벤트, 원본에 없는 리뷰, 평점 칸 경합으로 남은 옛 값, 회원 사본과 다른 판매자 칸.
 * 강의 앱은 가짜, 사본은 PostgreSQL 컨테이너, 문서는 nori ES 컨테이너다
 */
@RepositoryTest
@Import({SearchMemberCopyStore.class, LectureReviewCopyStore.class})
class LectureReviewReconcilerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T06:00:00Z"), SellerIndexer.ZONE);
    private static final long SELLER = 7L;
    private static final long LECTURE = 11L;

    @Autowired
    private SearchMemberCopyStore members;

    @Autowired
    private LectureReviewCopyStore reviews;

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final LectureIndexSetup indexSetup = new LectureIndexSetup(client);
    private final LectureDocumentStore documents = new LectureDocumentStore(client);
    private final FakeLectureSyncSource source = new FakeLectureSyncSource().pageSize(2);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private LectureIndexer indexer;
    private LectureReviewReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
        indexer = new LectureIndexer(indexSetup, documents, members, reviews, CLOCK);
        LectureCopyAligner aligner = new LectureCopyAligner(indexSetup, documents, members, reviews);
        reconciler = new LectureReviewReconciler(reviews, source, aligner, null, new LectureSyncMetrics(registry),
                CLOCK);
    }

    @Test
    @DisplayName("리뷰 작성·삭제 이벤트를 놓쳐도 다음 보정에서 리뷰 사본과 강의 문서의 평점 칸이 맞춰진다")
    void missedReviews() throws Exception {
        indexer.apply(lecture(LECTURE, 0L));
        indexer.apply(review(1L, 5, true, false, 0L));
        source.review(review(1L, 5, false, true, 1L))
                .review(review(2L, 4, true, false, 0L))
                .review(review(3L, 2, true, false, 0L));
        refresh();

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.COPIED)).isEqualTo(3);
        assertThat(result.count(LectureReconcileKind.RATING_FIXED)).isEqualTo(1);
        assertThat(source(LECTURE)).containsEntry("averageRating", 3.0).containsEntry("reviewCount", 2);
        assertThat(registry.get("hapbang.search.lecture.reconcile.items").tag("job", "review")
                .tag("kind", "rating_fixed").counter().count()).isEqualTo(1.0);

        refresh();
        LectureReconcileResult again = reconciler.reconcile();
        assertThat(again.fixed()).isZero();
        assertThat(again.count(LectureReconcileKind.ALREADY_APPLIED)).isEqualTo(3);
    }

    @Test
    @DisplayName("원본에 없는 리뷰는 사본에서 지우고 평점 칸을 다시 맞춘다")
    void deletesMissingReviews() throws Exception {
        indexer.apply(lecture(LECTURE, 0L));
        indexer.apply(review(1L, 5, true, false, 0L));
        indexer.apply(review(2L, 1, true, false, 0L));
        source.review(review(2L, 1, true, false, 0L));
        refresh();

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.REVIEW_DELETED)).isEqualTo(1);
        assertThat(reviews.ratingOf(LECTURE)).isEqualTo(new LectureRating(1.0, 1));
        assertThat(source(LECTURE)).containsEntry("averageRating", 1.0).containsEntry("reviewCount", 1);
    }

    @Test
    @DisplayName("평점 칸 경합으로 문서에 옛 평균이 남아 있으면(사본은 맞음) 다음 보정이 바로잡는다(Q1 a)")
    void fixesStaleRating() throws Exception {
        indexer.apply(lecture(LECTURE, 0L));
        indexer.apply(review(1L, 5, true, false, 0L));
        indexer.apply(review(2L, 3, true, false, 0L));
        // 먼저 읽고 늦게 쓴 옛 평균(리뷰 1개일 때)
        documents.writeRating(LECTURE, new LectureRating(5.0, 1));
        source.review(review(1L, 5, true, false, 0L)).review(review(2L, 3, true, false, 0L));
        refresh();

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.RATING_FIXED)).isEqualTo(1);
        assertThat(source(LECTURE)).containsEntry("averageRating", 4.0).containsEntry("reviewCount", 2);
    }

    @Test
    @DisplayName("판매자 칸이 회원 사본과 다르면 고치되, 문서가 더 새 회원 version이면 되돌리지 않는다. 사본에 없는 판매자는 건너뛴다")
    void alignsSeller() throws Exception {
        indexer.apply(lecture(LECTURE, 0L));
        indexer.apply(new LectureSyncDto(12L, 8L, "다른 판매자", "소개", 10_000L, true, false, List.of(), 0L));
        indexer.apply(new LectureSyncDto(13L, 9L, "사본 없는 판매자", "소개", 10_000L, true, false, List.of(), 0L));
        // 회원 이벤트의 문서 쓰기가 실패해 사본만 바뀐 판매자
        members.apply(new UserDto(SELLER, "새닉네임", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED, 2L),
                LocalDateTime.now(CLOCK));
        // 문서가 사본보다 새 값인 판매자(사본 행이 되돌려진 경우)
        members.apply(new UserDto(8L, "옛닉네임", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 1L),
                LocalDateTime.now(CLOCK));
        documents.writeSeller(12L, new Seller(8L, "새닉네임", true, 3L));
        refresh();

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.SELLER_FIXED)).isEqualTo(1);
        assertThat(source(LECTURE)).containsEntry("sellerNickname", "새닉네임").containsEntry("sellerActive", false)
                .containsEntry("sellerVersion", 2);
        assertThat(source(12L)).containsEntry("sellerNickname", "새닉네임").containsEntry("sellerVersion", 3);
        assertThat(source(13L)).containsEntry("sellerActive", true);
        assertThat(source(13L).get("sellerNickname")).isNull();
    }

    @Test
    @DisplayName("alias가 없으면(처음 적재 전) 리뷰 사본은 맞추고 칸 맞추기만 IndexNotReadyException으로 건너뛴다")
    void notReady() throws Exception {
        SearchElasticsearch.reset(client);
        source.review(review(1L, 5, true, false, 0L));

        assertThatThrownBy(reconciler::reconcile).isInstanceOf(IndexNotReadyException.class);
        assertThat(reviews.ratingOf(LECTURE)).isEqualTo(new LectureRating(5.0, 1));
        assertThat(registry.find("hapbang.search.lecture.reconcile.duration").timers()).isEmpty();
    }

    private void refresh() {
        indexSetup.refresh(LectureIndexDefinition.ALIAS);
    }

    private static LectureSyncDto lecture(long id, long version) {
        return new LectureSyncDto(id, SELLER, "강의", "소개", 10_000L, true, false,
                List.of(new LectureContentSyncDto(1L, "1강", 1)), version);
    }

    private static LectureReviewSyncDto review(long id, int rating, boolean counted, boolean deleted, long version) {
        return new LectureReviewSyncDto(id, LECTURE, 9L, rating, counted, deleted, version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long id) throws Exception {
        return client.get(g -> g.index(LectureIndexDefinition.ALIAS).id(String.valueOf(id)), Map.class).source();
    }
}
