package com.hapbang.lecture.in;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.global.exception.BusinessException;
import com.hapbang.lecture.app.LectureOrderFacade;
import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fake.FakeBillingPort;
import com.hapbang.lecture.fake.FakeLectureSettlementPort;
import com.hapbang.lecture.fake.FakeLectureSettlementPort.FakeSettlementErrorCode;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException;
import com.hapbang.shared.lecture.exception.LectureObjectionRefusedException.Reason;
import com.hapbang.shared.lecture.port.LectureObjectionPort;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 고객센터가 부르는 이의 제기 포트를 직접 호출해 DB·HP 반환과 지급 예정 취소(Fake billing)·트랜잭션 참여까지 확인한다.
 * 상태 규칙과 기한 경계는 LectureOrderObjectionTest가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class LectureObjectionPortTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long INQUIRY_ID = 500L;
    private static final String REASON = "강의 소개와 영상 내용이 다릅니다.";

    @Autowired
    private LectureObjectionPort lectureObjectionPort;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingPort billingPort;

    @Autowired
    private FakeLectureSettlementPort settlementPort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billingPort.reset();
        settlementPort.reset();
    }

    @Test
    void 접수하면_FILED와_문의ID와_사유가_저장되고_구매_취소는_거부된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();

        lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
        assertThat(item.getObjectionInquiryId()).isEqualTo(INQUIRY_ID);
        assertThat(item.getObjectionReason()).isEqualTo(REASON);
        assertThat(item.getObjectionFiledAt()).isNotNull();
        assertThatThrownBy(() -> lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.OBJECTION_FILED);
        assertThat(billingPort.releases()).isEmpty();
    }

    @Test
    void 없는_항목에는_이의_제기할_수_없다() {
        assertRefused(() -> lectureObjectionPort.fileObjection(999_999L, BUYER_ID, INQUIRY_ID, REASON),
                Reason.NOT_BUYER);
    }

    @Test
    void 지급_예정_시각이_지난_항목은_이의_제기할_수_없다() {
        LectureOrder expired = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("OBS 방송 세팅")), LocalDateTime.now(clock).minusDays(31)));
        Long orderItemId = expired.getItems().getFirst().getId();

        assertRefused(() -> lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON),
                Reason.PERIOD_EXPIRED);
        assertThat(item(orderItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
    }

    @Test
    void 인정하면_항목이_반환되고_그_항목의_보류_HP를_전부_반환하고_지급_예정을_취소한다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        lectureObjectionPort.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID);

        OrderItem item = item(orderItemId);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURNED);
        assertThat(item.getReturnedAt()).isNotNull();
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.ACCEPTED);
        assertThat(item.getObjectionResolvedAt()).isNotNull();
        assertThat(item.getObjectionResolvedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(billingPort.releases()).singleElement().satisfies(release -> {
            assertThat(release.userId()).isEqualTo(BUYER_ID);
            assertThat(release.referenceType()).isEqualTo(BillingReferenceType.LECTURE_ORDER_ITEM);
            assertThat(release.referenceId()).isEqualTo(orderItemId);
            assertThat(release.releaseRequestId()).isEqualTo("LECTURE-RETURN-" + orderItemId);
            assertThat(release.isFull()).isTrue();
        });
        assertThat(settlementPort.cancels()).containsExactly(orderItemId);
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 기각하면_REJECTED로_저장되고_HP는_움직이지_않는다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        lectureObjectionPort.resolveObjection(orderItemId, INQUIRY_ID, false, ADMIN_ID);

        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.REJECTED);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PURCHASED);
        assertThat(item.getObjectionResolvedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(billingPort.releases()).isEmpty();
        assertThat(settlementPort.cancels()).isEmpty();
    }

    @Test
    void 인정_반환액이_항목_가격과_다르면_판단을_롤백한다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
        billingPort.overrideReleasedHp(PRICE_HP - 1_000L);

        assertThatThrownBy(() -> lectureObjectionPort.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID))
                .isInstanceOf(IllegalStateException.class);

        assertFiledAndPurchased(orderItemId);
        assertThat(billingPort.releases()).isEmpty();
    }

    @Test
    void 지급_예정_취소가_실패하면_반환과_판단을_롤백한다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
        settlementPort.failCancelsWith(FakeSettlementErrorCode.SETTLEMENT_INVALID_STATE);

        assertThatThrownBy(() -> lectureObjectionPort.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID))
                .isInstanceOf(BusinessException.class);

        assertFiledAndPurchased(orderItemId);
        assertThat(billingPort.releases()).isEmpty();
        assertThat(settlementPort.cancels()).isEmpty();
    }

    @Test
    void billing이_반환을_거부하면_판단을_롤백한다() {
        // 보류 없이 저장한 주문이라 Fake billing이 반환을 거부한다
        LectureOrder saved = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("OBS 방송 세팅")), LocalDateTime.now(clock)));
        Long orderItemId = saved.getItems().getFirst().getId();
        lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        assertThatThrownBy(() -> lectureObjectionPort.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID))
                .isInstanceOf(IllegalStateException.class);

        assertFiledAndPurchased(orderItemId);
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, saved.getId()).status())
                .isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 같은_판단을_두_번_호출하면_두_번째는_거부되고_HP는_한_번만_반환된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
        lectureObjectionPort.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID);

        assertRefused(() -> lectureObjectionPort.resolveObjection(orderItemId, INQUIRY_ID, true, ADMIN_ID),
                Reason.NOT_FILED);
        assertThat(billingPort.releases()).hasSize(1);
    }

    @Test
    void 없는_항목과_다른_문의로는_판단할_수_없다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        assertRefused(() -> lectureObjectionPort.resolveObjection(999_999L, INQUIRY_ID, true, ADMIN_ID),
                Reason.NOT_FILED);
        assertRefused(() -> lectureObjectionPort.resolveObjection(999_999L, INQUIRY_ID, false, ADMIN_ID),
                Reason.NOT_FILED);
        assertRefused(() -> lectureObjectionPort.resolveObjection(orderItemId, 501L, false, ADMIN_ID),
                Reason.INQUIRY_MISMATCH);
        assertRefused(() -> lectureObjectionPort.resolveObjection(orderItemId, 501L, true, ADMIN_ID),
                Reason.INQUIRY_MISMATCH);
        assertFiledAndPurchased(orderItemId);
    }

    @Test
    void 호출한_쪽_트랜잭션이_롤백되면_접수도_롤백된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lectureObjectionPort.fileObjection(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
            throw new IllegalStateException("고객센터 문의 저장 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(item(orderItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
    }

    @Test
    void 접수_불가_예외를_잡고_삼키면_호출한_쪽_트랜잭션은_커밋되지_않는다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long firstItemId = order.items().getFirst().orderItemId();
        Long secondItemId = order.items().getLast().orderItemId();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lectureObjectionPort.fileObjection(firstItemId, BUYER_ID, INQUIRY_ID, REASON);
            try {
                lectureObjectionPort.fileObjection(secondItemId, 4L, 501L, REASON);
            } catch (LectureObjectionRefusedException ignored) {
                // 계약 위반: 다시 던지지 않는다
            }
        })).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(item(firstItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
    }

    @Test
    void 같은_주문의_마지막_두_항목을_동시에_인정해도_주문이_취소된다() throws Exception {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        List<Long> itemIds = order.items().stream().map(OrderItemResponse::orderItemId).toList();
        for (Long itemId : itemIds) {
            lectureObjectionPort.fileObjection(itemId, BUYER_ID, INQUIRY_ID + itemId, REASON);
        }
        // 두 판단이 모두 반환 단계에 들어갈 때까지 기다리게 해, 주문을 잠그지 않으면 서로의 반환을 보지 못하게 한다
        billingPort.holdReleasesUntil(2, Duration.ofSeconds(1));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = itemIds.stream()
                    .<Future<?>>map(itemId -> executor.submit(() ->
                            lectureObjectionPort.resolveObjection(itemId, INQUIRY_ID + itemId, true, ADMIN_ID)))
                    .toList();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.items()).extracting(OrderItemResponse::status).containsOnly(OrderItemStatus.RETURNED);
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.CANCELED);
        assertThat(billingPort.releases()).hasSize(2);
        assertThat(settlementPort.cancels()).containsExactlyInAnyOrderElementsOf(itemIds);
    }

    private OrderResponse order(Lecture... lectures) {
        return lectureOrderFacade.createOrder(BUYER_ID, List.of(lectures).stream().map(Lecture::getId).toList());
    }

    private OrderItem item(Long orderItemId) {
        return lectureOrderRepository.findItemOfBuyer(orderItemId, BUYER_ID).orElseThrow();
    }

    private void assertFiledAndPurchased(Long orderItemId) {
        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PURCHASED);
        assertThat(item.getReturnedAt()).isNull();
    }

    private Lecture saveOnSale(String title) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }

    private static void assertRefused(Runnable action, Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(LectureObjectionRefusedException.class)
                .extracting("reason").isEqualTo(reason);
    }
}
