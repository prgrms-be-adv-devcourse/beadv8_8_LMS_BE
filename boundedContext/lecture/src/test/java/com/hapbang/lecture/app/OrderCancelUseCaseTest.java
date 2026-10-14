package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fake.FakeBillingPort;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 구매 취소를 HP 반환(Fake billing)과 DB까지 확인한다. 기한 경계는 LectureOrderTest가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class OrderCancelUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long OTHER_BUYER_ID = 4L;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingPort billingPort;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billingPort.reset();
    }

    @Test
    void 구매_취소하면_그_항목의_보류_HP를_전부_반환한다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long canceledItemId = order.items().getFirst().orderItemId();

        OrderItemResponse response = lectureOrderFacade.cancel(BUYER_ID, order.orderId(), canceledItemId);

        assertThat(response.status()).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(response.canceledAt()).isNotNull();
        assertThat(billingPort.releases()).singleElement().satisfies(release -> {
            assertThat(release.userId()).isEqualTo(BUYER_ID);
            assertThat(release.referenceType()).isEqualTo(BillingReferenceType.LECTURE_ORDER_ITEM);
            assertThat(release.referenceId()).isEqualTo(canceledItemId);
            assertThat(release.releaseRequestId()).isEqualTo("LECTURE-CANCEL-" + canceledItemId);
            assertThat(release.isFull()).isTrue();
        });
        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(saved.items()).extracting(OrderItemResponse::status)
                .containsExactly(OrderItemStatus.CANCELED, OrderItemStatus.PURCHASED);
    }

    @Test
    void 마지막_항목을_구매_취소하면_주문도_취소된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));

        for (OrderItemResponse item : order.items()) {
            lectureOrderFacade.cancel(BUYER_ID, order.orderId(), item.orderItemId());
        }

        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.CANCELED);
        assertThat(saved.canceledAt()).isNotNull();
        assertThat(billingPort.releases()).extracting(ReleaseCommand::referenceId)
                .containsExactlyElementsOf(order.items().stream().map(OrderItemResponse::orderItemId).toList());
    }

    @Test
    void 마지막_두_항목을_동시에_구매_취소해도_주문이_취소된다() throws Exception {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        // 두 취소가 모두 반환 단계에 들어갈 때까지 기다리게 해, 주문을 잠그지 않으면 서로의 취소를 보지 못하게 한다
        billingPort.holdReleasesUntil(2, Duration.ofSeconds(1));
        SecurityContext securityContext = SecurityContextHolder.getContext();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<OrderItemResponse>> futures = order.items().stream()
                    .map(item -> executor.submit(() -> {
                        SecurityContextHolder.setContext(securityContext);
                        try {
                            return lectureOrderFacade.cancel(BUYER_ID, order.orderId(), item.orderItemId());
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    }))
                    .toList();
            for (Future<OrderItemResponse> future : futures) {
                assertThat(future.get().status()).isEqualTo(OrderItemStatus.CANCELED);
            }
        }

        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.items()).extracting(OrderItemResponse::status).containsOnly(OrderItemStatus.CANCELED);
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.CANCELED);
        assertThat(billingPort.releases()).hasSize(2);
    }

    @Test
    void 자동_구매_확정_시각이_지난_항목은_구매_취소할_수_없다() {
        LectureOrder expired = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("OBS 방송 세팅")), LocalDateTime.now(clock).minusDays(8)));
        Long orderItemId = expired.getItems().getFirst().getId();

        assertErrorCode(() -> lectureOrderFacade.cancel(BUYER_ID, expired.getId(), orderItemId),
                LectureErrorCode.CANCEL_PERIOD_EXPIRED);

        assertThat(billingPort.releases()).isEmpty();
        assertThat(itemStatus(expired.getId())).isEqualTo(OrderItemStatus.PURCHASED);
    }

    @Test
    void 남의_주문은_구매_취소할_수_없다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();

        assertErrorCode(() -> lectureOrderFacade.cancel(OTHER_BUYER_ID, order.orderId(), orderItemId),
                LectureErrorCode.ORDER_NOT_FOUND);
        assertThat(billingPort.releases()).isEmpty();
    }

    @Test
    void 반환액이_항목_가격과_다르면_구매_취소를_롤백한다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        billingPort.overrideReleasedHp(PRICE_HP - 1_000L);

        assertThatThrownBy(() -> lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(billingPort.releases()).isEmpty();
        assertThat(itemStatus(order.orderId())).isEqualTo(OrderItemStatus.PURCHASED);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자는_구매_취소할_수_없다() {
        LectureOrder saved = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("OBS 방송 세팅")), LocalDateTime.now(clock)));

        assertThatThrownBy(() -> lectureOrderFacade.cancel(BUYER_ID, saved.getId(),
                saved.getItems().getFirst().getId()))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(billingPort.releases()).isEmpty();
    }

    private OrderResponse order(Lecture... lectures) {
        return lectureOrderFacade.createOrder(BUYER_ID, List.of(lectures).stream().map(Lecture::getId).toList());
    }

    private OrderItemStatus itemStatus(Long orderId) {
        return lectureOrderFacade.getOrder(BUYER_ID, orderId).items().getFirst().status();
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private static void assertErrorCode(Runnable action, LectureErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
