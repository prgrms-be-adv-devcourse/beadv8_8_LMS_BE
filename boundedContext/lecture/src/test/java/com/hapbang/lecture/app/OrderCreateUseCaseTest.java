package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.global.exception.BusinessException;
import com.hapbang.lecture.app.dto.CartItemResponse;
import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.fake.FakeBillingPort;
import com.hapbang.lecture.fake.FakeLectureSettlementPort;
import com.hapbang.lecture.fake.FakeLectureSettlementPort.FakeSettlementErrorCode;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.LectureSettlementCommand;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 주문 생성을 HP 보류(Fake billing)와 DB까지 확인한다. 검증 순서·기한 계산 규칙은 LectureOrderTest가 맡는다.
 */
@IntegrationTest
@WithMockUser(roles = "USER")
class OrderCreateUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private CartFacade cartFacade;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingPort billingPort;

    @Autowired
    private FakeLectureSettlementPort settlementPort;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billingPort.reset();
        settlementPort.reset();
    }

    // 실패 설정이 같은 컨텍스트를 쓰는 다른 테스트 클래스로 넘어가지 않게 한다
    @AfterEach
    void tearDown() {
        settlementPort.reset();
    }

    @Test
    void 주문하면_항목마다_HP를_보류한다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", 5_000L);
        LocalDateTime before = LocalDateTime.now(clock);

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(first.getId(), second.getId()));

        assertThat(response.status()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(response.totalAmountHp()).isEqualTo(PRICE_HP + 5_000L);
        assertThat(response.items())
                .extracting(OrderItemResponse::lectureId, OrderItemResponse::lectureTitle, OrderItemResponse::priceHp,
                        OrderItemResponse::status)
                .containsExactly(
                        tuple(first.getId(), "OBS 방송 세팅", PRICE_HP, OrderItemStatus.PURCHASED),
                        tuple(second.getId(), "마이크 고르기", 5_000L, OrderItemStatus.PURCHASED));
        assertThat(response.items()).allSatisfy(item -> assertThat(item.purchasedAt()).isAfterOrEqualTo(before));
        assertThat(billingPort.holds())
                .extracting(HoldCommand::userId, HoldCommand::referenceType, HoldCommand::referenceId,
                        HoldCommand::amountHp)
                .containsExactly(
                        tuple(BUYER_ID, BillingReferenceType.LECTURE_ORDER_ITEM,
                                response.items().get(0).orderItemId(), PRICE_HP),
                        tuple(BUYER_ID, BillingReferenceType.LECTURE_ORDER_ITEM,
                                response.items().get(1).orderItemId(), 5_000L));
        // 보류 후 다시 정한 구매 시각이 저장된다 (D35)
        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, response.orderId());
        assertThat(saved.items()).extracting(OrderItemResponse::purchasedAt).containsOnly(saved.orderedAt());
        assertThat(saved.orderedAt()).isAfterOrEqualTo(before.withNano(0));
        // 항목마다 판매자 지급 예정이 항목 가격·구매 시각으로 등록된다 (#182)
        assertThat(settlementPort.schedules())
                .extracting(LectureSettlementCommand::buyerUserId, LectureSettlementCommand::sellerUserId,
                        LectureSettlementCommand::orderItemId, LectureSettlementCommand::amountHp)
                .containsExactly(
                        tuple(BUYER_ID, SELLER_ID, response.items().get(0).orderItemId(), PRICE_HP),
                        tuple(BUYER_ID, SELLER_ID, response.items().get(1).orderItemId(), 5_000L));
        assertThat(settlementPort.schedules()).extracting(LectureSettlementCommand::purchasedAt)
                .containsOnly(response.items().getFirst().purchasedAt().atZone(LectureConfig.ZONE).toInstant());
    }

    @Test
    void 판매자_지급_예정_등록이_실패하면_주문과_보류가_모두_롤백된다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", PRICE_HP);
        cartFacade.addItem(BUYER_ID, first.getId());
        settlementPort.failSchedulesWith(FakeSettlementErrorCode.WALLET_NOT_FOUND);
        long ordersBefore = lectureOrderRepository.count();

        assertThatThrownBy(() -> lectureOrderFacade.createOrder(BUYER_ID, List.of(first.getId(), second.getId())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("WALLET_NOT_FOUND");

        assertThat(lectureOrderRepository.count()).isEqualTo(ordersBefore);
        assertThat(cartLectureIds()).containsExactly(first.getId());
        assertThat(billingPort.holds()).isEmpty();
        assertThat(settlementPort.schedules()).isEmpty();
    }

    @Test
    void 주문하면_주문한_강의만_장바구니에서_빠진다() {
        Lecture ordered = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture remaining = saveOnSale("마이크 고르기", PRICE_HP);
        cartFacade.addItem(BUYER_ID, ordered.getId());
        cartFacade.addItem(BUYER_ID, remaining.getId());

        lectureOrderFacade.createOrder(BUYER_ID, List.of(ordered.getId()));

        assertThat(cartLectureIds()).containsExactly(remaining.getId());
    }

    @Test
    void 잔액이_부족하면_주문이_남지_않고_장바구니도_그대로다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", PRICE_HP);
        cartFacade.addItem(BUYER_ID, first.getId());
        cartFacade.addItem(BUYER_ID, second.getId());
        // 첫 항목은 보류되고 두 번째 항목에서 잔액이 모자란다
        billingPort.setBalance(BUYER_ID, PRICE_HP + 1_000L);
        long ordersBefore = lectureOrderRepository.count();

        assertThatThrownBy(() -> lectureOrderFacade.createOrder(BUYER_ID, List.of(first.getId(), second.getId())))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("INSUFFICIENT_BALANCE");

        assertThat(lectureOrderRepository.count()).isEqualTo(ordersBefore);
        assertThat(cartLectureIds()).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(billingPort.holds()).isEmpty();
        assertThat(settlementPort.schedules()).isEmpty();
    }

    @Test
    void 본인_강의는_주문할_수_없다() {
        Lecture own = saveOnSale("OBS 방송 세팅", PRICE_HP);

        assertRejected(SELLER_ID, List.of(own.getId()), LectureErrorCode.OWN_LECTURE_NOT_PURCHASABLE);
    }

    @Test
    void 판매_중이_아닌_강의는_주문할_수_없다() {
        Lecture pending = lectureRepository.save(
                Lecture.register(SELLER_ID, TITLE, DESCRIPTION, PRICE_HP, null, contentSpecs(), NOW));

        assertRejected(BUYER_ID, List.of(pending.getId()), LectureErrorCode.LECTURE_NOT_FOUND);
    }

    @Test
    void 없는_강의는_주문할_수_없다() {
        Lecture onSale = saveOnSale("OBS 방송 세팅", PRICE_HP);

        assertRejected(BUYER_ID, List.of(onSale.getId(), 999_999L), LectureErrorCode.LECTURE_NOT_FOUND);
    }

    @Test
    void 이미_보유한_강의는_주문할_수_없다() {
        Lecture owned = saveOnSale("OBS 방송 세팅", PRICE_HP);
        lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID, List.of(owned), LocalDateTime.now(clock)));

        assertRejected(BUYER_ID, List.of(owned.getId()), LectureErrorCode.ALREADY_OWNED_LECTURE);
    }

    @Test
    void 같은_강의를_두_번_넣으면_주문할_수_없다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅", PRICE_HP);

        assertRejected(BUYER_ID, List.of(lecture.getId(), lecture.getId()), LectureErrorCode.INVALID_REQUEST);
    }

    @Test
    void 같은_강의를_동시에_주문하면_하나만_성공한다() throws Exception {
        Long lectureId = saveOnSale("OBS 방송 세팅", PRICE_HP).getId();
        SecurityContext securityContext = SecurityContextHolder.getContext();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> order = () -> {
            SecurityContextHolder.setContext(securityContext);
            start.await();
            try {
                return lectureOrderFacade.createOrder(BUYER_ID, List.of(lectureId));
            } catch (LectureException e) {
                return e.getErrorCode();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        List<Object> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = List.of(executor.submit(order), executor.submit(order));
            start.countDown();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results).filteredOn(OrderResponse.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(LectureErrorCode.ALREADY_OWNED_LECTURE::equals).hasSize(1);
        assertThat(billingPort.holds()).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자는_주문할_수_없다() {
        Long lectureId = saveOnSale("OBS 방송 세팅", PRICE_HP).getId();

        assertThatThrownBy(() -> lectureOrderFacade.createOrder(ADMIN_ID, List.of(lectureId)))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(billingPort.holds()).isEmpty();
    }

    private void assertRejected(Long buyerUserId, List<Long> lectureIds, LectureErrorCode errorCode) {
        long ordersBefore = lectureOrderRepository.count();

        assertThatThrownBy(() -> lectureOrderFacade.createOrder(buyerUserId, lectureIds))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(errorCode);

        assertThat(lectureOrderRepository.count()).isEqualTo(ordersBefore);
        assertThat(billingPort.holds()).isEmpty();
    }

    private List<Long> cartLectureIds() {
        return cartFacade.getCart(BUYER_ID).items().stream().map(CartItemResponse::lectureId).toList();
    }

    private Lecture saveOnSale(String title, long priceHp) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, priceHp, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }
}
