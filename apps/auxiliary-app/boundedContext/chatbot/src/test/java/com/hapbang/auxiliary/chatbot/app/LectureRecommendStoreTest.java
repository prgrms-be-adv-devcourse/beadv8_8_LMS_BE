package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.domain.LectureRanker.Popular;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals.Activity;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals.Source;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/**
 * 강의 추천 사본 조회 SQL의 결과 (#1003). 쿼리를 정리해도 결과가 같은지 고정한다.
 * <p>
 * 판매자: 100·400 보임, 200 탈퇴, 300 마스킹, 500 사본 없음.
 */
class LectureRecommendStoreTest extends ChatbotIntegrationTestBase {

    private static final LocalDateTime T1 = RecommendRows.AT;
    private static final LocalDateTime T2 = RecommendRows.AT.plusDays(1);
    private static final LocalDateTime T3 = RecommendRows.AT.plusDays(2);

    @Autowired
    private LectureRecommendStore store;

    private RecommendRows data;

    @BeforeEach
    void setUp() {
        data = new RecommendRows(jdbcTemplate);
        data.member(100L);
        data.member(400L);
        data.member(200L, "WITHDRAWN");
        data.member(300L);
        rows.masked(300L);
        data.lecture(1L, 100L, "강의1", "소개1", List.of("1강", "2강"), true, false, false);
        data.lecture(2L, 100L, "강의2");
        data.lecture(3L, 400L, "강의3");
        data.lecture(4L, 200L, "탈퇴 판매자");
        data.lecture(5L, 300L, "마스킹 판매자");
        data.lecture(6L, 500L, "사본 없는 판매자");
        data.lecture(7L, 100L, "판매 중 아님", "소개", List.of(), false, false, false);
        data.lecture(8L, 100L, "삭제", "소개", List.of(), true, true, false);
        data.lecture(9L, 100L, "원본 없음", "소개", List.of(), true, false, true);
        data.lecture(10L, 400L, "강의10");
    }

    @Test
    @DisplayName("인기순: 구매(OWNED, 원본 있음) 수 내림차순, 같으면 ID 내림차순. 숨김 판매자·추천 불가 강의는 LIMIT 전에 거른다. "
            + "회원이 있으면 본인 강의, excluded가 있으면 그 강의를 뺀다")
    void popular() {
        popularPurchases();

        assertThat(store.popular(null, List.of(), 10)).extracting(Popular::lectureId).containsExactly(3L, 1L, 2L, 10L);
        assertThat(store.popular(null, List.of(), 2)).containsExactly(new Popular(3L, 400L), new Popular(1L, 100L));
        assertThat(store.popular(400L, List.of(), 10)).extracting(Popular::lectureId).containsExactly(1L, 2L);
        assertThat(store.popular(null, List.of(1L), 10)).extracting(Popular::lectureId).containsExactly(3L, 2L, 10L);
        assertThat(store.popular(100L, List.of(3L), 10)).extracting(Popular::lectureId).containsExactly(10L);
    }

    @Test
    @DisplayName("강의별 구매 수: OWNED이고 원본 있는 것만. 구매가 없거나 사본이 없는 ID는 0")
    void ownedCounts() {
        popularPurchases();

        assertThat(store.ownedCounts(List.of(1L, 2L, 10L, 99L)))
                .containsOnly(entry(1L, 2L), entry(2L, 1L), entry(10L, 0L), entry(99L, 0L));
        assertThat(store.ownedCounts(List.of())).isEmpty();
    }

    @Test
    @DisplayName("회원 구매·장바구니: OWNED·PENDING과 삭제 안 된 장바구니는 뺄 강의, 활동은 OWNED(구매 확정 시각, 없으면 주문 시각)와 "
            + "장바구니. 취소·원본 없음·다른 회원은 없다")
    void memberLectures() {
        long me = 900L;
        data.purchase(1L, me, 1L, "OWNED", T1);
        data.purchase(2L, me, 2L, "PENDING", T1);
        data.purchase(3L, me, 3L, "CANCELED", T1);
        data.purchase(4L, me, 10L, "OWNED", T3);
        jdbcTemplate.update("UPDATE chatbot.lecture_purchase_copy SET ordered_at = ?, purchased_at = NULL WHERE order_item_id = 4",
                T2);
        data.purchase(5L, me, 7L, "OWNED", T1);
        jdbcTemplate.update("UPDATE chatbot.lecture_purchase_copy SET missing_in_source = TRUE WHERE order_item_id = 5");
        data.purchase(6L, 901L, 4L, "OWNED", T1);
        data.cart(1L, me, 8L, false, T3);
        data.cart(2L, me, 9L, true, T3);
        data.cart(3L, me, 5L, false, T3);
        jdbcTemplate.update("UPDATE chatbot.cart_item_copy SET missing_in_source = TRUE WHERE id = 3");
        data.cart(4L, 901L, 6L, false, T3);

        LectureRecommendStore.MemberLectures mine = store.memberLectures(me);

        assertThat(mine.excluded()).containsExactlyInAnyOrder(1L, 2L, 10L, 8L);
        assertThat(mine.activities()).containsExactlyInAnyOrder(new Activity(1L, Source.PURCHASED, T1),
                new Activity(10L, Source.PURCHASED, T2), new Activity(8L, Source.CART, T3));
        assertThat(mine.activities().getLast().source()).isEqualTo(Source.CART);
        assertThat(store.memberLectures(999L).excluded()).isEmpty();
    }

    @Test
    @DisplayName("회원 리뷰 별점: 평점에 세는 리뷰만(안 셈·삭제·원본 없음·다른 회원 제외)")
    void ratings() {
        long me = 900L;
        data.review(1L, me, 1L, 5);
        data.review(2L, me, 2L, 1);
        data.review(3L, me, 3L, 4);
        data.review(4L, me, 10L, 3);
        data.review(5L, 901L, 7L, 2);
        jdbcTemplate.update("UPDATE chatbot.lecture_review_copy SET counted = FALSE WHERE id = 2");
        jdbcTemplate.update("UPDATE chatbot.lecture_review_copy SET deleted = TRUE WHERE id = 3");
        jdbcTemplate.update("UPDATE chatbot.lecture_review_copy SET missing_in_source = TRUE WHERE id = 4");

        assertThat(store.ratings(me)).containsOnly(entry(1L, 5));
    }

    @Test
    @DisplayName("추천 목록용 사본: 판매자 보임(탈퇴·마스킹·사본 없음은 false), 상태 값 그대로, 없는 ID는 빠진다")
    void listings() {
        var listings = store.listings(List.of(1L, 4L, 5L, 6L, 7L, 8L, 9L, 99L));

        assertThat(listings).containsOnlyKeys(1L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(listings.get(1L).sellerVisible()).isTrue();
        assertThat(listings.get(1L).title()).isEqualTo("강의1");
        assertThat(listings.get(1L).description()).isEqualTo("소개1");
        assertThat(listings.get(4L).sellerVisible()).isFalse();
        assertThat(listings.get(5L).sellerVisible()).isFalse();
        assertThat(listings.get(6L).sellerVisible()).isFalse();
        assertThat(listings.get(7L).onSale()).isFalse();
        assertThat(listings.get(8L).deleted()).isTrue();
        assertThat(listings.get(9L).missingInSource()).isTrue();
        assertThat(store.listings(List.of())).isEmpty();
    }

    @Test
    @DisplayName("색인용 사본: 콘텐츠 제목 순서 그대로, 없는 ID는 빠진다. lecturesAfter는 ID 다음부터 오름차순 limit개")
    void lectures() {
        assertThat(store.lectures(List.of(1L, 99L))).containsOnly(entry(1L,
                new LectureSnapshot(1L, 100L, "강의1", "소개1", List.of("1강", "2강"), true, false, false)));
        assertThat(store.lectures(List.of())).isEmpty();
        assertThat(store.lecturesAfter(1L, 2)).extracting(LectureSnapshot::id).containsExactly(2L, 3L);
        assertThat(store.lecturesAfter(8L, 10)).extracting(LectureSnapshot::id).containsExactly(9L, 10L);
        assertThat(store.lecturesAfter(8L, 10).getFirst().missingInSource()).isTrue();
    }

    // 강의 4(탈퇴 판매자) 5건, 1·3 각 2건, 2 1건(+ PENDING 3건), 10 0건(+ 원본 없음 3건)
    private void popularPurchases() {
        long orderItemId = 1000L;
        for (int i = 0; i < 5; i++) {
            data.purchase(orderItemId++, 9000L + i, 4L, "OWNED", T1);
        }
        for (long lectureId : List.of(1L, 3L, 1L, 3L, 2L)) {
            data.purchase(orderItemId++, 9100L + orderItemId, lectureId, "OWNED", T1);
        }
        for (int i = 0; i < 3; i++) {
            data.purchase(orderItemId++, 9200L + i, 2L, "PENDING", T1);
        }
        for (int i = 0; i < 3; i++) {
            data.purchase(orderItemId, 9300L + i, 10L, "OWNED", T1);
            jdbcTemplate.update("UPDATE chatbot.lecture_purchase_copy SET missing_in_source = TRUE WHERE order_item_id = ?",
                    orderItemId);
            orderItemId++;
        }
    }
}
