package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.app.dto.OrderSummaryResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.ConfirmationType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.domain.OrderProgress;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 주문 조회와 직접 구매 확정을 DB까지 확인한다. 주문은 HP 보류 없이 픽스처로 저장한다(주문 생성·구매 취소는 OrderCreateUseCaseTest·OrderCancelUseCaseTest).
 * 세션 역할은 {@code @WithMockUser}로 넣는다 (Facade의 {@code @PreAuthorize} 검사용).
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class LectureOrderFacadeTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long OTHER_BUYER_ID = 4L;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @Test
    void 내_주문_목록을_최신순으로_조회한다() {
        Lecture first = saveOnSale("OBS 방송 세팅");
        Lecture second = saveOnSale("마이크 고르기");
        LectureOrder older = save(BUYER_ID, List.of(first, second), now().minusDays(1));
        LectureOrder newer = save(BUYER_ID, List.of(saveOnSale("조명 세팅")), now());
        save(OTHER_BUYER_ID, List.of(first), now());

        Page<OrderSummaryResponse> orders = lectureOrderFacade.getOrders(BUYER_ID, PageRequest.of(0, 10));

        assertThat(orders.getContent())
                .extracting(OrderSummaryResponse::orderId, OrderSummaryResponse::representativeTitle,
                        OrderSummaryResponse::itemCount, OrderSummaryResponse::totalAmountHp,
                        OrderSummaryResponse::status, OrderSummaryResponse::progress)
                .containsExactly(
                        tuple(newer.getId(), "조명 세팅", 1, PRICE_HP, LectureOrderStatus.PAID, OrderProgress.IN_PROGRESS),
                        tuple(older.getId(), "OBS 방송 세팅", 2, PRICE_HP * 2, LectureOrderStatus.PAID,
                                OrderProgress.IN_PROGRESS));
    }

    @Test
    void 주문_상세에_항목_상태와_기한이_담긴다() {
        LocalDateTime purchasedAt = LocalDateTime.of(2026, 9, 30, 10, 0);
        LectureOrder order = save(BUYER_ID, List.of(saveOnSale("OBS 방송 세팅")), purchasedAt);

        OrderResponse response = lectureOrderFacade.getOrder(BUYER_ID, order.getId());

        assertThat(response.orderId()).isEqualTo(order.getId());
        assertThat(response.status()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(response.progress()).isEqualTo(OrderProgress.IN_PROGRESS);
        assertThat(response.orderedAt()).isEqualTo(purchasedAt);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.orderItemId()).isEqualTo(order.getItems().getFirst().getId());
            assertThat(item.lectureTitle()).isEqualTo("OBS 방송 세팅");
            assertThat(item.priceHp()).isEqualTo(PRICE_HP);
            assertThat(item.status()).isEqualTo(OrderItemStatus.PURCHASED);
            assertThat(item.purchasedAt()).isEqualTo(purchasedAt);
            assertThat(item.autoConfirmAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
            assertThat(item.payoutScheduledAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 0, 0));
            assertThat(item.objectionStatus()).isEqualTo(ObjectionStatus.NONE);
        });
    }

    @Test
    void 남의_주문은_없는_주문으로_응답한다() {
        LectureOrder order = save(OTHER_BUYER_ID, List.of(saveOnSale("OBS 방송 세팅")), now());
        Long orderItemId = order.getItems().getFirst().getId();

        assertErrorCode(() -> lectureOrderFacade.getOrder(BUYER_ID, order.getId()), LectureErrorCode.ORDER_NOT_FOUND);
        assertErrorCode(() -> lectureOrderFacade.confirm(BUYER_ID, order.getId(), orderItemId),
                LectureErrorCode.ORDER_NOT_FOUND);
        assertErrorCode(() -> lectureOrderFacade.getOrder(BUYER_ID, 999L), LectureErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    void 구매를_확정하면_저장된다() {
        LectureOrder order = save(BUYER_ID, List.of(saveOnSale("OBS 방송 세팅")), now());
        Long orderItemId = order.getItems().getFirst().getId();

        OrderItemResponse response = lectureOrderFacade.confirm(BUYER_ID, order.getId(), orderItemId);

        assertThat(response.status()).isEqualTo(OrderItemStatus.CONFIRMED);
        assertThat(response.confirmationType()).isEqualTo(ConfirmationType.DIRECT);
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.getId()).items().getFirst().status())
                .isEqualTo(OrderItemStatus.CONFIRMED);
    }

    @Test
    void 자동_확정_시각이_지난_항목을_확정하면_자동_확정으로_저장된다() {
        LectureOrder order = save(BUYER_ID, List.of(saveOnSale("OBS 방송 세팅")), now().minusDays(10));
        Long orderItemId = order.getItems().getFirst().getId();

        OrderItemResponse response = lectureOrderFacade.confirm(BUYER_ID, order.getId(), orderItemId);

        assertThat(response.confirmationType()).isEqualTo(ConfirmationType.AUTO);
        assertThat(response.confirmedAt()).isEqualTo(order.getItems().getFirst().getAutoConfirmAt());
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.getId()).items().getFirst().confirmationType())
                .isEqualTo(ConfirmationType.AUTO);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자는_주문을_조회_확정할_수_없다() {
        LectureOrder order = save(BUYER_ID, List.of(saveOnSale("OBS 방송 세팅")), now());
        Long orderItemId = order.getItems().getFirst().getId();

        assertThatThrownBy(() -> lectureOrderFacade.getOrders(BUYER_ID, PageRequest.of(0, 10)))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> lectureOrderFacade.getOrder(BUYER_ID, order.getId()))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> lectureOrderFacade.confirm(BUYER_ID, order.getId(), orderItemId))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private LectureOrder save(Long buyerUserId, List<Lecture> lectures, LocalDateTime purchasedAt) {
        return lectureOrderRepository.save(LectureOrderFixture.order(buyerUserId, lectures, purchasedAt));
    }

    private static void assertErrorCode(Runnable action, LectureErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
