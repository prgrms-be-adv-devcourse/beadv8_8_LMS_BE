package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.domain.Cart;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureReview;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.CartRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.LectureReviewRepository;
import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

// AI 추천용 동기화 내부 API(#712)를 내부 API 보안 체인부터 DB까지 관통해 검증한다. 이벤트와 같은 값인지는 LectureSync*EventTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureSyncInternalApiTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final String SYNC = "/api/v1/internal/lectures/sync";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private LectureReviewRepository lectureReviewRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 강의_목록은_ID_오름차순_키셋이고_마지막_페이지는_nextAfterId가_없다() throws Exception {
        Long first = saveOnSale("1번 강의").getId();
        Long second = saveOnSale("2번 강의").getId();
        Long third = saveOnSale("3번 강의").getId();

        mockMvc.perform(internal(get(SYNC + "/lectures").param("afterId", String.valueOf(first - 1))
                        .param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("강의 동기화 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[*].id").value(contains(first.intValue(), second.intValue())))
                .andExpect(jsonPath("$.data.nextAfterId").value(second));
        mockMvc.perform(internal(get(SYNC + "/lectures").param("afterId", String.valueOf(second))
                        .param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id").value(contains(third.intValue())))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist());
        // afterId를 생략하면 처음부터, size를 생략하면 100개까지다
        mockMvc.perform(internal(get(SYNC + "/lectures")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist());
    }

    @Test
    void 강의_목록은_삭제된_강의와_검수_대기_반려_강의도_싣고_콘텐츠는_지우지_않은_것만_순서대로_싣는다() throws Exception {
        Lecture pending = lectureRepository.save(pendingLecture());
        Lecture rejected = lectureRepository.save(rejectedLecture());
        inTransaction(() -> lectureRepository.findById(rejected.getId()).orElseThrow().replaceContents(SELLER_ID,
                List.of(new LectureContentSpec(201L, "다시 찍은 1강", ContentType.VIDEO)), NOW.plusMinutes(1)));
        Lecture deleted = saveOnSale("지운 강의");
        inTransaction(() -> lectureRepository.findById(deleted.getId()).orElseThrow().delete(SELLER_ID, NOW));

        mockMvc.perform(internal(get(SYNC + "/lectures").param("afterId", String.valueOf(pending.getId() - 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(pending.getId()))
                .andExpect(jsonPath("$.data.items[0].sellerUserId").value(SELLER_ID))
                .andExpect(jsonPath("$.data.items[0].title").value(TITLE))
                .andExpect(jsonPath("$.data.items[0].description").value(DESCRIPTION))
                .andExpect(jsonPath("$.data.items[0].onSale").value(false))
                .andExpect(jsonPath("$.data.items[0].deleted").value(false))
                .andExpect(jsonPath("$.data.items[0].contents[*].title").value(contains("1강 장비 고르기", "장비 목록")))
                .andExpect(jsonPath("$.data.items[0].contents[*].sequence").value(contains(1, 2)))
                .andExpect(jsonPath("$.data.items[0].version").value(0))
                .andExpect(jsonPath("$.data.items[0].priceHp").value(PRICE_HP))
                // 썸네일·검수 정보는 싣지 않는다
                .andExpect(jsonPath("$.data.items[0].thumbnailFileId").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].id").value(rejected.getId()))
                .andExpect(jsonPath("$.data.items[1].contents[*].title").value(contains("다시 찍은 1강")))
                .andExpect(jsonPath("$.data.items[1].version").value(1))
                .andExpect(jsonPath("$.data.items[2].id").value(deleted.getId()))
                .andExpect(jsonPath("$.data.items[2].onSale").value(false))
                .andExpect(jsonPath("$.data.items[2].deleted").value(true));
    }

    @Test
    void 구매_목록은_결제_전이거나_취소된_항목도_싣고_결제_전이면_purchasedAt이_빠진다() throws Exception {
        LectureOrder pending = lectureOrderRepository.save(LectureOrderFixture.pending(BUYER_ID,
                List.of(saveOnSale("결제 중 강의")), NOW));
        LectureOrder canceled = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("취소한 강의")), NOW));
        Long canceledItemId = itemId(canceled);
        inTransaction(() -> LectureOrderFixture.cancel(lectureOrderRepository.findByItemId(canceledItemId)
                .orElseThrow(), canceledItemId, NOW));

        mockMvc.perform(internal(get(SYNC + "/purchases").param("afterId", String.valueOf(itemId(pending) - 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("구매 동기화 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[0].orderItemId").value(itemId(pending)))
                .andExpect(jsonPath("$.data.items[0].buyerUserId").value(BUYER_ID))
                .andExpect(jsonPath("$.data.items[0].lectureId").value(pending.getItems().getFirst().getLectureId()))
                .andExpect(jsonPath("$.data.items[0].result").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].orderedAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.data.items[0].purchasedAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].version").value(0))
                // 금액은 싣지 않는다
                .andExpect(jsonPath("$.data.items[0].priceHp").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].orderItemId").value(canceledItemId))
                .andExpect(jsonPath("$.data.items[1].result").value("CANCELED"))
                .andExpect(jsonPath("$.data.items[1].purchasedAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist());
    }

    @Test
    void 장바구니_항목_목록은_뺀_항목도_deleted_true로_싣는다() throws Exception {
        Long kept = saveOnSale("담아 둔 강의").getId();
        Long removed = saveOnSale("뺀 강의").getId();
        inTransaction(() -> {
            Cart cart = cartRepository.save(Cart.create(BUYER_ID, NOW));
            cart.add(lectureRepository.findById(kept).orElseThrow(), NOW);
            cart.add(lectureRepository.findById(removed).orElseThrow(), NOW);
            cartRepository.flush();
            cart.remove(removed, NOW.plusMinutes(1));
        });

        mockMvc.perform(internal(get(SYNC + "/cart-items")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("장바구니 항목 동기화 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[*].lectureId").value(contains(kept.intValue(), removed.intValue())))
                .andExpect(jsonPath("$.data.items[*].userId").value(contains(BUYER_ID.intValue(), BUYER_ID.intValue())))
                .andExpect(jsonPath("$.data.items[*].deleted").value(contains(false, true)))
                .andExpect(jsonPath("$.data.items[*].version").value(contains(0, 1)))
                .andExpect(jsonPath("$.data.items[0].addedAt").value(endsWith("+09:00")));
    }

    @Test
    void 리뷰_목록은_지운_리뷰와_반환된_항목의_리뷰도_싣고_평점_반영_여부를_알려_준다() throws Exception {
        Long counted = review(saveOnSale("좋은 강의"), OrderItemStatus.CONFIRMED, 5).getId();
        LectureReview deleted = review(saveOnSale("지운 리뷰 강의"), OrderItemStatus.CONFIRMED, 4);
        inTransaction(() -> lectureReviewRepository.findById(deleted.getId()).orElseThrow().delete(BUYER_ID, NOW));
        Long returned = review(saveOnSale("반환한 강의"), OrderItemStatus.RETURNED, 1).getId();

        mockMvc.perform(internal(get(SYNC + "/reviews").param("afterId", String.valueOf(counted - 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("리뷰 동기화 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[*].id").value(
                        contains(counted.intValue(), deleted.getId().intValue(), returned.intValue())))
                .andExpect(jsonPath("$.data.items[*].reviewerUserId").value(
                        contains(BUYER_ID.intValue(), BUYER_ID.intValue(), BUYER_ID.intValue())))
                .andExpect(jsonPath("$.data.items[*].rating").value(contains(5, 4, 1)))
                .andExpect(jsonPath("$.data.items[*].counted").value(contains(true, false, false)))
                .andExpect(jsonPath("$.data.items[*].deleted").value(contains(false, true, false)))
                .andExpect(jsonPath("$.data.items[*].version").value(contains(0, 1, 0)))
                // 리뷰 본문은 싣지 않는다
                .andExpect(jsonPath("$.data.items[0].content").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({
            "lectures, size, 0", "lectures, size, 501", "lectures, afterId, -1",
            "purchases, size, 0", "purchases, size, 501", "purchases, afterId, -1",
            "cart-items, size, 0", "cart-items, size, 501", "cart-items, afterId, -1",
            "reviews, size, 0", "reviews, size, 501", "reviews, afterId, -1"
    })
    void size가_1에서_500_밖이거나_afterId가_음수면_400_INVALID_REQUEST다(String kind, String param, String value)
            throws Exception {
        mockMvc.perform(internal(get(SYNC + "/" + kind).param(param, value)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value(param));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lectures", "purchases", "cart-items", "reviews"})
    void 내부_키가_없거나_틀리면_본문_없이_401이고_회원_로그인으로도_부를_수_없다(String kind) throws Exception {
        mockMvc.perform(get(SYNC + "/" + kind))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get(SYNC + "/" + kind)
                        .header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get(SYNC + "/" + kind).with(GatewayLogin.as(BUYER_ID, Role.ADMIN)))
                .andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    /** 구매 확정한 항목에 리뷰를 쓰고 항목 상태를 바꾼다(반환은 HP 작업 없이 상태만) */
    private LectureReview review(Lecture lecture, OrderItemStatus itemStatus, int rating) {
        return inTransaction(() -> {
            LectureOrder order = lectureOrderRepository.save(
                    LectureOrderFixture.order(BUYER_ID, List.of(lecture), NOW));
            OrderItem item = order.getItems().getFirst();
            lectureOrderRepository.flush();
            order.confirmItem(item.getId(), NOW);
            LectureReview review = lectureReviewRepository.save(LectureReview.write(item, BUYER_ID, rating, "본문", NOW));
            ReflectionTestUtils.setField(item, "status", itemStatus);
            return review;
        });
    }

    private static Long itemId(LectureOrder order) {
        return order.getItems().getFirst().getId();
    }

    private void inTransaction(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> action.run());
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }
}
