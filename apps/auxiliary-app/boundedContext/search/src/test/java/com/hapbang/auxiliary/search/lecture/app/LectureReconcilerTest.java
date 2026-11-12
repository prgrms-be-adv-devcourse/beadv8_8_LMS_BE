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
import com.hapbang.auxiliary.search.lecture.out.IndexedLecture;
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
 * 강의 보정(#884 L13 완료 조건): 놓친 강의 등록·수정·판매 중지가 다음 보정에서 반영되고, 원본에 없는 문서가 숨겨진다. 강의 앱은 가짜, 사본은 PostgreSQL
 * 컨테이너, 문서는 nori ES 컨테이너다
 */
@RepositoryTest
@Import({SearchMemberCopyStore.class, LectureReviewCopyStore.class})
class LectureReconcilerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T06:00:00Z"), SellerIndexer.ZONE);
    private static final long SELLER = 7L;

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
    private LectureReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
        indexer = new LectureIndexer(indexSetup, documents, members, reviews, CLOCK);
        reconciler = new LectureReconciler(indexSetup, documents, indexer, source, null,
                new LectureSyncMetrics(registry), CLOCK);
    }

    @Test
    @DisplayName("등록 이벤트를 놓친 강의는 다음 보정에서 문서를 만들고 판매자·평점 칸을 사본에서 채운다")
    void missedRegistration() throws Exception {
        members.apply(new UserDto(SELLER, "편집장", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 1L),
                LocalDateTime.now(CLOCK));
        reviews.apply(new LectureReviewSyncDto(1L, 11L, 9L, 4, true, false, 0L), LocalDateTime.now(CLOCK));
        source.lecture(lecture(11L, "프리미어 컷편집", true, 0L));

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.INSERTED)).isEqualTo(1);
        assertThat(result.fixed()).isEqualTo(1);
        assertThat(source(11L)).containsEntry("title", "프리미어 컷편집").containsEntry("hidden", false)
                .containsEntry("sellerNickname", "편집장").containsEntry("averageRating", 4.0)
                .containsEntry("reviewCount", 1);
        assertThat(registry.get("hapbang.search.lecture.reconcile.items").tag("job", "lecture")
                .tag("kind", "inserted").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("hapbang.search.lecture.reconcile.duration").tag("job", "lecture")
                .tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("수정·판매 중지 이벤트를 놓친 강의는 다음 보정에서 고치고, 이미 반영한 강의는 쓰지 않는다")
    void missedChanges() throws Exception {
        indexer.apply(lecture(11L, "처음", true, 0L));
        indexer.apply(lecture(12L, "판매 중", true, 0L));
        indexer.apply(lecture(13L, "그대로", true, 4L));
        source.lecture(lecture(11L, "수정", true, 1L))
                .lecture(lecture(12L, "판매 중", false, 1L))
                .lecture(lecture(13L, "그대로", true, 4L));

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.OVERWRITTEN)).isEqualTo(2);
        assertThat(result.count(LectureReconcileKind.ALREADY_APPLIED)).isEqualTo(1);
        assertThat(result.read()).isEqualTo(3);
        assertThat(source(11L)).containsEntry("title", "수정").containsEntry("lectureVersion", 1);
        assertThat(source(12L)).containsEntry("hidden", true);
        assertThat(reconciler.reconcile().fixed()).isZero();
    }

    @Test
    @DisplayName("문서가 원본보다 새 version이면(보정이 읽은 뒤 이벤트가 반영) 되돌리지 않는다")
    void newerDocumentWins() throws Exception {
        indexer.apply(lecture(11L, "새 제목", true, 5L));
        source.lecture(lecture(11L, "옛 제목", true, 4L));

        assertThat(reconciler.reconcile().count(LectureReconcileKind.ALREADY_APPLIED)).isEqualTo(1);
        assertThat(source(11L)).containsEntry("title", "새 제목");
    }

    @Test
    @DisplayName("원본에 없는 보이는 문서는 숨기고, 읽는 동안 생긴 강의(마지막으로 읽은 ID보다 큰 ID)는 남긴다")
    void hidesMissing() throws Exception {
        indexer.apply(lecture(11L, "원본에 있음", true, 0L));
        indexer.apply(lecture(12L, "원본에서 사라짐", true, 0L));
        indexer.apply(lecture(20L, "읽는 동안 생김", true, 0L));
        source.lecture(lecture(11L, "원본에 있음", true, 0L)).lecture(lecture(13L, "새 강의", true, 0L));
        indexSetup.refresh(LectureIndexDefinition.ALIAS);

        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.HIDDEN)).isEqualTo(1);
        assertThat(source(12L)).containsEntry("hidden", true).doesNotContainKey("lectureVersion");
        assertThat(source(20L)).containsEntry("hidden", false);
    }

    @Test
    @DisplayName("원본에 없다고 숨긴 강의가 원본에 다시 나타나면(늦게 커밋된 강의) 같은 version이어도 다음 보정에서 다시 보인다")
    void reappears() throws Exception {
        indexer.apply(lecture(11L, "있음", true, 0L));
        indexer.apply(lecture(12L, "늦게 커밋", true, 3L));
        source.lecture(lecture(11L, "있음", true, 0L)).lecture(lecture(13L, "다음", true, 0L));
        indexSetup.refresh(LectureIndexDefinition.ALIAS);
        reconciler.reconcile();
        assertThat(source(12L)).containsEntry("hidden", true);

        source.lecture(lecture(12L, "늦게 커밋", true, 3L));
        LectureReconcileResult result = reconciler.reconcile();

        assertThat(result.count(LectureReconcileKind.OVERWRITTEN)).isEqualTo(1);
        assertThat(source(12L)).containsEntry("hidden", false).containsEntry("lectureVersion", 3);
    }

    @Test
    @DisplayName("원본이 비어 있으면(읽은 강의 0개) 문서를 숨기지 않는다")
    void emptySourceHidesNothing() throws Exception {
        indexer.apply(lecture(11L, "제목", true, 0L));
        indexSetup.refresh(LectureIndexDefinition.ALIAS);

        assertThat(reconciler.reconcile().read()).isZero();
        assertThat(source(11L)).containsEntry("hidden", false);
    }

    @Test
    @DisplayName("비교는 문서가 없거나, 강의 version이 원본보다 작거나 없을 때만 다시 쓴다")
    void needsWrite() {
        LectureSyncDto lecture = lecture(11L, "제목", true, 3L);
        Seller seller = new Seller(SELLER, null, true, null);

        assertThat(LectureReconciler.needsWrite(lecture, null)).isTrue();
        assertThat(LectureReconciler.needsWrite(lecture, indexed(null, seller))).isTrue();
        assertThat(LectureReconciler.needsWrite(lecture, indexed(2L, seller))).isTrue();
        assertThat(LectureReconciler.needsWrite(lecture, indexed(3L, seller))).isFalse();
        assertThat(LectureReconciler.needsWrite(lecture, indexed(4L, seller))).isFalse();
    }

    @Test
    @DisplayName("alias가 없으면(처음 적재 전) 강의 앱을 부르지 않고 IndexNotReadyException으로 알린다")
    void notReady() throws Exception {
        SearchElasticsearch.reset(client);
        source.lecture(lecture(11L, "제목", true, 0L));

        assertThatThrownBy(reconciler::reconcile).isInstanceOf(IndexNotReadyException.class);
        assertThat(source.lectureCalls()).isZero();
        assertThat(registry.find("hapbang.search.lecture.reconcile.duration").timers()).isEmpty();
    }

    private static IndexedLecture indexed(Long version, Seller seller) {
        return new IndexedLecture(11L, version, false, seller, LectureRating.NONE);
    }

    private static LectureSyncDto lecture(long id, String title, boolean onSale, long version) {
        return new LectureSyncDto(id, SELLER, title, "소개", 10_000L, onSale, false,
                List.of(new LectureContentSyncDto(1L, "1강", 1)), version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(long id) throws Exception {
        return client.get(g -> g.index(LectureIndexDefinition.ALIAS).id(String.valueOf(id)), Map.class).source();
    }
}
