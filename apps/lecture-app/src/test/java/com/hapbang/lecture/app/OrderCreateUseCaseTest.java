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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.app.dto.CartItemResponse;
import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.domain.ProcessingStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.out.BillingCallFailureKind;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.billing.out.BillingCallExceptions;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 주문 생성을 HP 보류 작업(Fake 지갑 Client)과 DB까지 확인한다 (D58, D68, D69). 검증 순서·기한 계산 규칙은 LectureOrderTest가,
 * 결제 단계 판정은 OrderHoldTasksTest가 맡는다.
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
    private FakeBillingApiClient billing;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
    }

    @Test
    void 모든_보류가_성공하면_구매_완료이고_주문_트랜잭션_밖에서_항목마다_보류한다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", 5_000L);
        LocalDateTime before = LocalDateTime.now(clock);

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(first.getId(), second.getId()));

        assertThat(response.status()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(response.processingStatus()).isNull();
        assertThat(response.totalAmountHp()).isEqualTo(PRICE_HP + 5_000L);
        assertThat(response.items())
                .extracting(OrderItemResponse::lectureId, OrderItemResponse::lectureTitle, OrderItemResponse::priceHp,
                        OrderItemResponse::status)
                .containsExactly(
                        tuple(first.getId(), "OBS 방송 세팅", PRICE_HP, OrderItemStatus.PURCHASED),
                        tuple(second.getId(), "마이크 고르기", 5_000L, OrderItemStatus.PURCHASED));
        assertThat(billing.calls()).allSatisfy(call -> assertThat(call.transactionActive()).isFalse());
        assertThat(holds())
                .extracting(HoldCommand::userId, HoldCommand::referenceType, HoldCommand::referenceId,
                        HoldCommand::amountHp)
                .containsExactly(
                        tuple(BUYER_ID, BillingReferenceType.LECTURE_ORDER_ITEM,
                                response.items().get(0).orderItemId(), PRICE_HP),
                        tuple(BUYER_ID, BillingReferenceType.LECTURE_ORDER_ITEM,
                                response.items().get(1).orderItemId(), 5_000L));
        // 주문 시각은 시도한 시각, 구매 시각은 결제가 끝난 시각이다 (D35, D58 (5))
        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, response.orderId());
        assertThat(saved.paidAt()).isAfterOrEqualTo(saved.orderedAt());
        assertThat(saved.orderedAt()).isAfterOrEqualTo(before.withNano(0));
        assertThat(saved.items()).extracting(OrderItemResponse::purchasedAt).containsOnly(saved.paidAt());
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
    void 첫_항목이_잔액_부족이면_반환_없이_결제_실패이고_남은_보류는_시도하지_않는다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", PRICE_HP);
        cartFacade.addItem(BUYER_ID, first.getId());
        cartFacade.addItem(BUYER_ID, second.getId());
        billing.respond(command -> insufficientBalance());

        assertInsufficientBalance(List.of(first.getId(), second.getId()));

        assertThat(holds()).hasSize(1);
        assertThat(releases()).isEmpty();
        assertThat(onlyOrderStatus()).isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(cartLectureIds()).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    void 일부_보류가_성공한_뒤_거절되면_성공한_보류만_한_번_반환하고_결제_실패다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", PRICE_HP);
        Lecture third = saveOnSale("조명 세팅", PRICE_HP);
        billing.respond(this::heldInFull);
        billing.respond(command -> insufficientBalance());

        assertInsufficientBalance(List.of(first.getId(), second.getId(), third.getId()));

        Long heldItemId = holds().getFirst().referenceId();
        assertThat(holds()).hasSize(2);
        assertThat(releases()).singleElement().satisfies(release -> {
            assertThat(release.referenceId()).isEqualTo(heldItemId);
            assertThat(release.releaseRequestId()).isEqualTo("LECTURE-HOLD-COMPENSATE-" + heldItemId);
        });
        assertThat(onlyOrderStatus()).isEqualTo(LectureOrderStatus.HOLD_FAILED);
        // 결제 실패한 강의는 다시 살 수 있다 (D58 (7))
        assertThat(lectureOrderFacade.createOrder(BUYER_ID, List.of(first.getId())).status())
                .isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 보류_결과를_모르면_결제_처리_중으로_응답하고_배치가_구매_완료로_마무리한다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅", PRICE_HP);
        cartFacade.addItem(BUYER_ID, lecture.getId());
        billing.respond(command -> {
            throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 0, null);
        });

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()));

        assertThat(response.status()).isEqualTo(LectureOrderStatus.HOLD_PENDING);
        assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.DELAYED);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(OrderItemStatus.HOLD_PENDING);
            assertThat(item.processingStatus()).isEqualTo(ProcessingStatus.DELAYED);
            assertThat(item.purchasedAt()).isNull();
        });
        assertThat(cartLectureIds()).containsExactly(lecture.getId());

        runDueTasks();

        OrderResponse paid = lectureOrderFacade.getOrder(BUYER_ID, response.orderId());
        assertThat(paid.status()).isEqualTo(LectureOrderStatus.PAID);
        assertThat(paid.processingStatus()).isNull();
        assertThat(holds()).extracting(HoldCommand::referenceId).containsOnly(paid.items().getFirst().orderItemId());
        assertThat(cartLectureIds()).isEmpty();
    }

    @Test
    void 반환이_끝나지_않으면_결제_취소_처리_중으로_응답하고_배치가_결제_실패로_마무리한다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", PRICE_HP);
        billing.respond(this::heldInFull);
        billing.respond(command -> insufficientBalance());
        billing.respond(command -> {
            throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 0, null);
        });

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(first.getId(), second.getId()));

        assertThat(response.status()).isEqualTo(LectureOrderStatus.HOLD_COMPENSATING);
        assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.DELAYED);

        runDueTasks();

        assertThat(lectureOrderFacade.getOrder(BUYER_ID, response.orderId()).status())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(releases()).extracting(ReleaseCommand::releaseRequestId).containsOnly(
                "LECTURE-HOLD-COMPENSATE-" + response.items().getFirst().orderItemId());
    }

    @Test
    void 결과를_모르던_보류가_결제_취소_처리_중에_성공하면_그_항목도_반환한다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", PRICE_HP);
        Lecture third = saveOnSale("조명 세팅", PRICE_HP);
        billing.respond(command -> {
            throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 0, null);
        });
        billing.respond(command -> insufficientBalance());

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID,
                List.of(first.getId(), second.getId(), third.getId()));

        assertThat(response.status()).isEqualTo(LectureOrderStatus.HOLD_COMPENSATING);
        // 거절 뒤 실행하지 않은 세 번째 보류는 닫는다
        assertThat(holds()).hasSize(2);
        assertThat(releases()).isEmpty();

        runDueTasks();
        runDueTasks();

        Long unknownItemId = response.items().getFirst().orderItemId();
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, response.orderId()).status())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(holds()).hasSize(3);
        assertThat(releases()).extracting(ReleaseCommand::referenceId).containsExactly(unknownItemId);
    }

    @Test
    void 반환_결과를_기록하지_못해도_같은_요청_ID로_다시_반환해_결제_실패로_수렴한다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", PRICE_HP);
        billing.respond(this::heldInFull);
        billing.respond(command -> insufficientBalance());
        // 반환을 호출하는 동안 다른 실행이 선점을 가져가, 첫 반환 결과는 늦은 결과로 버려진다
        billing.setDuringCall(() -> jdbcTemplate.update("""
                update lectures.lecture_hp_task set claim_token = gen_random_uuid()
                where purpose = 'HOLD_COMPENSATION' and status = 'PROCESSING'"""));

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(first.getId(), second.getId()));

        assertThat(response.status()).isEqualTo(LectureOrderStatus.HOLD_COMPENSATING);
        billing.setDuringCall(() -> {
        });

        runDueTasks();

        assertThat(lectureOrderFacade.getOrder(BUYER_ID, response.orderId()).status())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(releases()).hasSize(2)
                .extracting(ReleaseCommand::releaseRequestId).containsOnly(
                        "LECTURE-HOLD-COMPENSATE-" + response.items().getFirst().orderItemId());
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
        assertThat(holds()).hasSize(1);
    }

    @Test
    void 다건_주문은_강의_ID_오름차순으로_항목을_만든다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", 5_000L);

        OrderResponse response = lectureOrderFacade.createOrder(BUYER_ID, List.of(second.getId(), first.getId()));

        assertThat(response.items()).extracting(OrderItemResponse::lectureId)
                .containsExactly(first.getId(), second.getId());
        // 항목 INSERT(IDENTITY)도 같은 순서로 실행된다 (D55)
        assertThat(response.items()).extracting(OrderItemResponse::orderItemId).isSorted();
    }

    @Test
    void 반대_순서로_동시에_다건_주문하면_하나는_성공하고_하나는_ALREADY_OWNED_LECTURE다() throws Exception {
        // 두 주문이 같은 유일 인덱스 키를 서로 다른 순서로 잡으면 교착(40P01, 500)이 난다. 여러 번 겨뤄 확인한다
        SecurityContext securityContext = SecurityContextHolder.getContext();
        for (int round = 0; round < 10; round++) {
            Long first = saveOnSale("OBS 방송 세팅", PRICE_HP).getId();
            Long second = saveOnSale("마이크 고르기", 5_000L).getId();
            CountDownLatch start = new CountDownLatch(1);
            List<Object> results = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                List<Future<Object>> futures = List.of(
                        executor.submit(orderOnSignal(securityContext, start, List.of(first, second))),
                        executor.submit(orderOnSignal(securityContext, start, List.of(second, first))));
                start.countDown();
                for (Future<Object> future : futures) {
                    results.add(future.get(30, TimeUnit.SECONDS));
                }
            }

            assertThat(results).as("round %d: %s", round, results)
                    .filteredOn(OrderResponse.class::isInstance).hasSize(1);
            assertThat(results).as("round %d: %s", round, results)
                    .filteredOn(LectureErrorCode.ALREADY_OWNED_LECTURE::equals).hasSize(1);
        }
        // 실패한 주문은 보류 작업을 남기기 전에 롤백되고, 성공한 주문의 항목 2개만 보류된다
        assertThat(holds()).hasSize(20);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 관리자는_주문할_수_없다() {
        Long lectureId = saveOnSale("OBS 방송 세팅", PRICE_HP).getId();

        assertThatThrownBy(() -> lectureOrderFacade.createOrder(ADMIN_ID, List.of(lectureId)))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(holds()).isEmpty();
    }

    /** 신호를 받으면 주문한다. 업무 실패는 오류 코드로, 그 밖의 예외(교착 등)는 예외 그대로 돌려준다 */
    private Callable<Object> orderOnSignal(SecurityContext securityContext, CountDownLatch start,
                                           List<Long> lectureIds) {
        return () -> {
            SecurityContextHolder.setContext(securityContext);
            start.await();
            try {
                return lectureOrderFacade.createOrder(BUYER_ID, lectureIds);
            } catch (LectureException e) {
                return e.getErrorCode();
            } catch (RuntimeException e) {
                return e;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private void assertRejected(Long buyerUserId, List<Long> lectureIds, LectureErrorCode errorCode) {
        long ordersBefore = lectureOrderRepository.count();

        assertThatThrownBy(() -> lectureOrderFacade.createOrder(buyerUserId, lectureIds))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(errorCode);

        assertThat(lectureOrderRepository.count()).isEqualTo(ordersBefore);
        assertThat(holds()).isEmpty();
    }

    private void assertInsufficientBalance(List<Long> lectureIds) {
        assertThatThrownBy(() -> lectureOrderFacade.createOrder(BUYER_ID, lectureIds))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.INSUFFICIENT_BALANCE);
        assertThat(LectureErrorCode.INSUFFICIENT_BALANCE.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    /** 실행할 때가 된 HP 작업을 배치로 실행한다. 재시도를 기다리는 작업은 시각을 당긴다 */
    private void runDueTasks() {
        jdbcTemplate.update("update lectures.lecture_hp_task set next_attempt_at = now() - interval '10 minutes', "
                + "lease_until = case when lease_until is null then null else now() - interval '10 minutes' end "
                + "where status in ('PENDING', 'PROCESSING')");
        lectureHpTaskBatchUseCase.runDue();
    }

    private LectureOrderStatus onlyOrderStatus() {
        return lectureOrderRepository.findAll().stream()
                .filter(order -> order.getBuyerUserId().equals(BUYER_ID))
                .map(order -> order.getStatus())
                .reduce((a, b) -> {
                    throw new AssertionError("구매자의 주문이 하나가 아닙니다.");
                })
                .orElseThrow();
    }

    private List<HoldCommand> holds() {
        return billing.calls().stream()
                .map(FakeBillingApiClient.Call::command)
                .filter(HoldCommand.class::isInstance)
                .map(HoldCommand.class::cast)
                .toList();
    }

    private List<ReleaseCommand> releases() {
        return billing.calls().stream()
                .map(FakeBillingApiClient.Call::command)
                .filter(ReleaseCommand.class::isInstance)
                .map(ReleaseCommand.class::cast)
                .toList();
    }

    private BillingCallResult<?> heldInFull(Object command) {
        HoldCommand hold = (HoldCommand) command;
        return new BillingCallResult.Succeeded<>(new HoldResultDto(
                hold.referenceType(), hold.referenceId(), hold.amountHp(), false));
    }

    private static BillingCallResult<?> insufficientBalance() {
        return new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE, "HP 잔액이 부족합니다.", 409,
                "INSUFFICIENT_BALANCE");
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
