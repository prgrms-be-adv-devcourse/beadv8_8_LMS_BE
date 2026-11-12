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
import com.hapbang.auxiliary.search.common.out.WriteOutcome;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexSetup;
import com.hapbang.auxiliary.search.lecture.out.LectureRating;
import com.hapbang.auxiliary.search.lecture.out.LectureReviewCopyStore;
import com.hapbang.auxiliary.search.lecture.out.SearchMemberCopyStore;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.RepositoryTest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * 강의·리뷰·회원 정보를 강의 검색 문서에 반영(#882 완료 조건): 강의 등록·수정·판매 중지·삭제, 리뷰 작성·수정·삭제, 판매자 닉네임·상태 변경,
 * 순서 역전, 처음 적재 전. 사본은 PostgreSQL 컨테이너, 문서는 nori ES 컨테이너다
 */
@RepositoryTest
@Import({SearchMemberCopyStore.class, LectureReviewCopyStore.class})
class LectureIndexerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T06:00:00Z"), SellerIndexer.ZONE);
    private static final long SELLER = 7L;

    @Autowired
    private SearchMemberCopyStore members;

    @Autowired
    private LectureReviewCopyStore reviews;

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final LectureIndexSetup indexSetup = new LectureIndexSetup(client);
    private final LectureDocumentStore documents = new LectureDocumentStore(client);

    private LectureIndexer lectureIndexer;
    private SellerIndexer sellerIndexer;

    @BeforeEach
    void setUp() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
        lectureIndexer = new LectureIndexer(indexSetup, documents, members, reviews, CLOCK);
        sellerIndexer = new SellerIndexer(indexSetup, documents, members, CLOCK);
    }

    @Test
    @DisplayName("강의 등록은 회원 사본의 판매자 닉네임과 먼저 온 리뷰의 평점으로 문서를 만든다")
    void register() throws Exception {
        sellerIndexer.apply(member("편집장", UserStatus.ACTIVE, 1L));
        lectureIndexer.apply(review(1L, 5, true, 0L));
        lectureIndexer.apply(review(2L, 4, true, 0L));

        assertThat(lectureIndexer.apply(lecture("프리미어 컷편집", true, 0L)).outcome()).isEqualTo(WriteOutcome.CREATED);

        assertThat(source()).containsEntry("title", "프리미어 컷편집").containsEntry("hidden", false)
                .containsEntry("sellerNickname", "편집장").containsEntry("sellerActive", true)
                .containsEntry("averageRating", 4.5).containsEntry("reviewCount", 2);
    }

    @Test
    @DisplayName("강의 수정은 반영하고, 판매 중지·삭제는 숨기며, 순서가 뒤바뀐 옛 수정은 무시한다")
    void modifyStopDelete() throws Exception {
        lectureIndexer.apply(lecture("처음", true, 0L));

        lectureIndexer.apply(lecture("수정", true, 1L));
        assertThat(source()).containsEntry("title", "수정").containsEntry("hidden", false);

        lectureIndexer.apply(lecture("수정", false, 2L));
        assertThat(source()).containsEntry("hidden", true);

        assertThat(lectureIndexer.apply(lecture("수정", true, 1L)).outcome()).isEqualTo(WriteOutcome.NOOP);

        lectureIndexer.apply(new LectureSyncDto(11L, SELLER, "수정", "소개", false, true, List.of(), 3L));
        assertThat(source()).containsEntry("hidden", true).containsEntry("lectureVersion", 3);
        assertThat(lectureIndexer.apply(lecture("되살리기", true, 2L)).outcome()).isEqualTo(WriteOutcome.NOOP);
        assertThat(source()).containsEntry("hidden", true);
    }

    @Test
    @DisplayName("리뷰 작성·수정·삭제마다 평균·리뷰 수를 다시 세고, 늦게 온 옛 리뷰는 평점을 바꾸지 못한다")
    void reviews() throws Exception {
        lectureIndexer.apply(lecture("제목", true, 0L));

        lectureIndexer.apply(review(1L, 5, true, 0L));
        lectureIndexer.apply(review(2L, 3, true, 0L));
        assertThat(source()).containsEntry("averageRating", 4.0).containsEntry("reviewCount", 2);

        lectureIndexer.apply(review(2L, 1, true, 1L));
        assertThat(source()).containsEntry("averageRating", 3.0);

        lectureIndexer.apply(review(1L, 5, false, true, 1L));
        assertThat(source()).containsEntry("averageRating", 1.0).containsEntry("reviewCount", 1);

        assertThat(lectureIndexer.apply(review(1L, 5, true, 0L)).copied()).isFalse();
        assertThat(source()).containsEntry("averageRating", 1.0).containsEntry("reviewCount", 1);
    }

    @Test
    @DisplayName("강의 문서가 없을 때 온 리뷰는 사본에만 남고 문서는 건너뛴다")
    void reviewBeforeLecture() {
        assertThat(lectureIndexer.apply(review(1L, 5, true, 0L)).outcome()).isEqualTo(WriteOutcome.MISSING);
        assertThat(reviews.ratingOf(11L)).isEqualTo(new LectureRating(5.0, 1));
    }

    @Test
    @DisplayName("판매자 닉네임 변경·정지는 그 판매자의 강의 문서를 고치고, 정지는 숨김이 아니라 sellerActive=false다")
    void sellerChanges() throws Exception {
        sellerIndexer.apply(member("편집장", UserStatus.ACTIVE, 1L));
        lectureIndexer.apply(lecture("제목", true, 0L));

        assertThat(sellerIndexer.apply(member("새닉네임", UserStatus.ACTIVE, 2L)).documents()).isEqualTo(1);
        assertThat(source()).containsEntry("sellerNickname", "새닉네임");

        sellerIndexer.apply(member("새닉네임", UserStatus.SUSPENDED, 3L));
        assertThat(source()).containsEntry("sellerActive", false).containsEntry("hidden", false);

        // 프로필 사진처럼 닉네임·상태가 같은 변경은 문서를 고치지 않는다
        assertThat(sellerIndexer.apply(member("새닉네임", UserStatus.SUSPENDED, 4L)).sellerChanged()).isFalse();

        // 늦게 온 옛 이벤트는 정지를 풀지 못한다
        assertThat(sellerIndexer.apply(member("편집장", UserStatus.ACTIVE, 2L)).sellerChanged()).isFalse();
        assertThat(source()).containsEntry("sellerActive", false);
    }

    @Test
    @DisplayName("사본은 고쳤는데 문서를 고치지 못해 같은 회원 이벤트를 다시 받으면 문서를 다시 맞춘다")
    void retryAfterDocumentFailure() throws Exception {
        sellerIndexer.apply(member("편집장", UserStatus.ACTIVE, 1L));
        lectureIndexer.apply(lecture("제목", true, 0L));
        members.apply(member("새닉네임", UserStatus.ACTIVE, 2L), LocalDateTime.now(CLOCK));

        sellerIndexer.apply(member("새닉네임", UserStatus.ACTIVE, 2L));

        assertThat(source()).containsEntry("sellerNickname", "새닉네임");
    }

    @Test
    @DisplayName("사본을 읽은 뒤 문서를 만들기 전에 판매자·리뷰가 바뀌어도 문서를 만든 뒤 다시 읽어 맞춘다")
    void catchesUpCopiesChangedWhileCreating() throws Exception {
        sellerIndexer.apply(member("편집장", UserStatus.ACTIVE, 1L));
        LectureDocumentStore racing = new LectureDocumentStore(client) {
            @Override
            public WriteOutcome writeLecture(LectureSyncDto lecture, Seller seller, LectureRating rating, Instant now) {
                // 아직 문서가 없어 이 회원·리뷰 이벤트는 사본만 고친다
                sellerIndexer.apply(member("새닉네임", UserStatus.SUSPENDED, 2L));
                reviews.apply(review(1L, 2, true, 0L), LocalDateTime.now(CLOCK));
                return super.writeLecture(lecture, seller, rating, now);
            }
        };

        new LectureIndexer(indexSetup, racing, members, reviews, CLOCK).apply(lecture("제목", true, 0L));

        assertThat(source()).containsEntry("sellerNickname", "새닉네임").containsEntry("sellerActive", false)
                .containsEntry("averageRating", 2.0).containsEntry("reviewCount", 1);
    }

    @Test
    @DisplayName("처음 적재 전(alias 없음)에는 사본만 고치고 문서 쓰기는 IndexNotReadyException으로 알린다")
    void beforeFirstLoad() throws Exception {
        SearchElasticsearch.reset(client);

        assertThatThrownBy(() -> sellerIndexer.apply(member("편집장", UserStatus.ACTIVE, 1L)))
                .isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> lectureIndexer.apply(review(1L, 5, true, 0L)))
                .isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> lectureIndexer.apply(lecture("제목", true, 0L)))
                .isInstanceOf(IndexNotReadyException.class);

        assertThat(members.findSeller(SELLER)).contains(new Seller(SELLER, "편집장", true));
        assertThat(reviews.ratingOf(11L)).isEqualTo(new LectureRating(5.0, 1));
        assertThat(client.indices().exists(e -> e.index(LectureIndexDefinition.ALIAS)).value()).isFalse();
    }

    @Test
    @DisplayName("필수 값이 없는 정보는 다시 처리해도 같으므로 IllegalArgumentException이다")
    void requiresIds() {
        assertThatThrownBy(() -> lectureIndexer.apply((LectureSyncDto) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lectureIndexer.apply((LectureReviewSyncDto) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sellerIndexer.apply(new UserDto(SELLER, "닉", Role.USER, null, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LectureSyncDto lecture(String title, boolean onSale, long version) {
        return new LectureSyncDto(11L, SELLER, title, "소개", onSale, false,
                List.of(new LectureContentSyncDto(1L, "1강", 1)), version);
    }

    private static LectureReviewSyncDto review(long id, int rating, boolean counted, long version) {
        return review(id, rating, counted, false, version);
    }

    private static LectureReviewSyncDto review(long id, int rating, boolean counted, boolean deleted, long version) {
        return new LectureReviewSyncDto(id, 11L, 3L, rating, counted, deleted, version);
    }

    private static UserDto member(String nickname, UserStatus status, Long version) {
        return new UserDto(SELLER, nickname, Role.VERIFIED_INFLUENCER, status, version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source() throws Exception {
        return client.get(g -> g.index(LectureIndexDefinition.ALIAS).id("11"), Map.class).source();
    }
}
