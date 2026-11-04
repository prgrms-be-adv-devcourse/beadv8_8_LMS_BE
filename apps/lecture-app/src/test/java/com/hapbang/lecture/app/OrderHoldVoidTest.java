package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.lecture.app.dto.LectureHpTaskResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskOutcome;
import com.hapbang.lecture.domain.LectureHpTaskPurpose;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureHpTaskType;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.ProcessingStatus;
import com.hapbang.lecture.fake.FakeBillingApiClient;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.out.BillingCallExceptions;
import com.hapbang.shared.billing.out.BillingCallFailureKind;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 결과를 확정하지 못한 주문 보류를 관리자가 보류 무효화로 끝내는 흐름을 Fake 지갑 Client와 DB까지 확인한다 (D73, #535).
 * 실패 분류는 LectureHpTaskClassifierTest, 결제 단계 판정은 OrderHoldTasksTest, HTTP 응답은 LectureHpTaskAdminApiTest가 맡는다.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
// 주문은 구매자, 보류 무효화·재시도는 관리자 진입점이다
@WithMockUser(roles = {"USER", "ADMIN"})
class OrderHoldVoidTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureHpTaskAdminFacade lectureHpTaskAdminFacade;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private FakeBillingApiClient billing;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.setSettledHp(PRICE_HP);
    }

    @Test
    void DEAD_보류를_무효화하면_결제_취소_처리_중을_거쳐_결제_실패이고_같은_강의를_다시_살_수_있다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅", PRICE_HP);
        billing.respond(command -> invariantViolation());
        OrderResponse order = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()));
        assertThat(order.status()).isEqualTo(LectureOrderStatus.HOLD_PENDING);
        assertThat(order.processingStatus()).isEqualTo(ProcessingStatus.ACTION_REQUIRED);
        Long itemId = order.items().getFirst().orderItemId();
        LectureHpTask dead = deadHold(order.orderId());

        LectureHpTaskResponse voidTask = lectureHpTaskAdminFacade.voidHold(ADMIN_ID, dead.getId());

        assertThat(voidTask.taskType()).isEqualTo(LectureHpTaskType.VOID);
        assertThat(voidTask.purpose()).isEqualTo(LectureHpTaskPurpose.HOLD_VOID);
        assertThat(voidTask.status()).isEqualTo(LectureHpTaskStatus.PENDING);
        assertThat(voidTask.requestId()).isEqualTo("LECTURE-HOLD-VOID-" + itemId);
        assertThat(voidTask.orderItemId()).isEqualTo(itemId);
        assertThat(voidTask.payerUserId()).isEqualTo(BUYER_ID);
        assertThat(voidTask.amountHp()).isEqualTo(PRICE_HP);
        LectureHpTask closed = lectureHpTaskRepository.findById(dead.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(LectureHpTaskStatus.COMPLETED);
        assertThat(closed.getOutcome()).isEqualTo(LectureHpTaskOutcome.VOIDED);
        assertThat(closed.getVoidedByUserId()).isEqualTo(ADMIN_ID);
        // 등록만 하고 지갑에는 배치가 보낸다. 그동안 주문은 결제 취소 처리 중이다
        assertThat(voids()).isEmpty();
        OrderResponse compensating = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(compensating.status()).isEqualTo(LectureOrderStatus.HOLD_COMPENSATING);
        assertThat(compensating.processingStatus()).isEqualTo(ProcessingStatus.PROCESSING);

        runDueTasks();

        assertThat(voids()).singleElement().isEqualTo(
                new HoldVoidCommand(BUYER_ID, BillingReferenceType.LECTURE_ORDER_ITEM, itemId));
        OrderResponse failed = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(failed.status()).isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(failed.processingStatus()).isNull();
        assertThat(lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId())).status())
                .isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 지갑에_보류가_있었으면_무효화가_반환하고_결제_실패다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅", PRICE_HP);
        billing.respond(command -> unknownResultAfterRetries());
        OrderResponse order = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()));
        markHoldsDead(order.orderId());
        billing.respond(command -> voided((HoldVoidCommand) command, HoldVoidOutcome.RELEASED, PRICE_HP));

        lectureHpTaskAdminFacade.voidHold(ADMIN_ID, deadHold(order.orderId()).getId());
        runDueTasks();

        assertThat(voids()).hasSize(1);
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
        assertThat(task(order.orderId(), LectureHpTaskPurpose.HOLD_VOID).getOutcome())
                .isEqualTo(LectureHpTaskOutcome.SUCCEEDED);
    }

    @Test
    void 여러_항목_중_하나만_DEAD면_성공한_항목은_보상_반환하고_DEAD_항목은_무효화해_결제_실패다() {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", PRICE_HP);
        Lecture third = saveOnSale("조명 세팅", PRICE_HP);
        billing.respond(this::heldInFull);
        billing.respond(command -> invariantViolation());
        billing.respond(this::heldInFull);
        OrderResponse order = lectureOrderFacade.createOrder(BUYER_ID,
                List.of(first.getId(), second.getId(), third.getId()));
        assertThat(order.status()).isEqualTo(LectureOrderStatus.HOLD_PENDING);
        Long firstItemId = order.items().get(0).orderItemId();
        Long deadItemId = order.items().get(1).orderItemId();
        Long thirdItemId = order.items().get(2).orderItemId();

        lectureHpTaskAdminFacade.voidHold(ADMIN_ID, deadHold(order.orderId()).getId());
        runDueTasks();

        assertThat(releases()).extracting(ReleaseCommand::releaseRequestId).containsExactlyInAnyOrder(
                "LECTURE-HOLD-COMPENSATE-" + firstItemId, "LECTURE-HOLD-COMPENSATE-" + thirdItemId);
        assertThat(voids()).extracting(HoldVoidCommand::referenceId).containsExactly(deadItemId);
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
    }

    @Test
    void 무효화_결과를_모르면_재시도하고_처음_결과를_돌려받아_결제_실패로_마무리한다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅", PRICE_HP);
        billing.respond(command -> invariantViolation());
        OrderResponse order = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()));
        lectureHpTaskAdminFacade.voidHold(ADMIN_ID, deadHold(order.orderId()).getId());
        billing.respond(command -> {
            throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 500, "HOLD_STATE_INCONSISTENT");
        });
        billing.respond(command -> new BillingCallResult.Succeeded<>(new HoldVoidResultDto(
                BillingReferenceType.LECTURE_ORDER_ITEM, ((HoldVoidCommand) command).referenceId(),
                HoldVoidOutcome.RELEASED, PRICE_HP, true)));

        runDueTasks();

        OrderResponse delayed = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(delayed.status()).isEqualTo(LectureOrderStatus.HOLD_COMPENSATING);
        assertThat(delayed.processingStatus()).isEqualTo(ProcessingStatus.DELAYED);

        runDueTasks();

        assertThat(voids()).hasSize(2);
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
    }

    @Test
    void 무효화가_DEAD면_결제_취소_처리_중에_남고_무효화_작업을_재시도해_마무리한다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅", PRICE_HP);
        billing.respond(command -> invariantViolation());
        OrderResponse order = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()));
        LectureHpTaskResponse voidTask = lectureHpTaskAdminFacade.voidHold(ADMIN_ID,
                deadHold(order.orderId()).getId());
        billing.respond(command -> new BillingCallResult.Failed<>(BillingFailureCode.HOLD_OWNER_MISMATCH,
                "다른 회원의 보류입니다.", 409, "HOLD_OWNER_MISMATCH"));

        runDueTasks();

        assertThat(lectureHpTaskRepository.findById(voidTask.id()).orElseThrow().getStatus())
                .isEqualTo(LectureHpTaskStatus.DEAD);
        OrderResponse stuck = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(stuck.status()).isEqualTo(LectureOrderStatus.HOLD_COMPENSATING);
        assertThat(stuck.processingStatus()).isEqualTo(ProcessingStatus.ACTION_REQUIRED);

        lectureHpTaskAdminFacade.retry(ADMIN_ID, voidTask.id());
        runDueTasks();

        assertThat(voids()).hasSize(2);
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
    }

    @Test
    void 예상과_다른_무효화_결과도_DEAD로_두지_않고_결제_실패로_끝낸다(CapturedOutput output) {
        Lecture first = saveOnSale("OBS 방송 세팅", PRICE_HP);
        Lecture second = saveOnSale("마이크 고르기", PRICE_HP);
        billing.respond(command -> invariantViolation());
        billing.respond(command -> invariantViolation());
        OrderResponse order = lectureOrderFacade.createOrder(BUYER_ID, List.of(first.getId(), second.getId()));
        for (LectureHpTask dead : deadHolds(order.orderId())) {
            lectureHpTaskAdminFacade.voidHold(ADMIN_ID, dead.getId());
        }
        // 반환 금액이 항목 가격과 다르거나, 보류가 이미 끝나 있었다 (D73 (4))
        billing.respond(command -> voided((HoldVoidCommand) command, HoldVoidOutcome.RELEASED, PRICE_HP - 1));
        billing.respond(command -> voided((HoldVoidCommand) command, HoldVoidOutcome.ALREADY_CLOSED, 0L));

        runDueTasks();

        assertThat(voids()).hasSize(2);
        assertThat(tasks(order.orderId(), LectureHpTaskPurpose.HOLD_VOID))
                .extracting(LectureHpTask::getStatus, LectureHpTask::getOutcome)
                .containsOnly(tuple(LectureHpTaskStatus.COMPLETED, LectureHpTaskOutcome.SUCCEEDED));
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.HOLD_FAILED);
        // 반환 금액이 다른 건만 사람이 확인하도록 표식을 붙인 ERROR로 남긴다
        assertThat(output.getAll().lines()
                .filter(line -> line.contains(LectureHpTaskExecutor.VOID_AMOUNT_MISMATCH_LOG)))
                .singleElement()
                .satisfies(line -> assertThat(line).contains(" ERROR ", "releasedHp=" + (PRICE_HP - 1)));
    }

    @Test
    void 무효화한_보류는_다시_보내거나_다시_무효화할_수_없다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅", PRICE_HP);
        billing.respond(command -> invariantViolation());
        OrderResponse order = lectureOrderFacade.createOrder(BUYER_ID, List.of(lecture.getId()));
        Long holdTaskId = deadHold(order.orderId()).getId();
        lectureHpTaskAdminFacade.voidHold(ADMIN_ID, holdTaskId);

        assertThatThrownBy(() -> lectureHpTaskAdminFacade.retry(ADMIN_ID, holdTaskId))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.HP_TASK_NOT_DEAD);
        assertThatThrownBy(() -> lectureHpTaskAdminFacade.voidHold(ADMIN_ID, holdTaskId))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.HP_TASK_NOT_DEAD);
        assertThat(tasks(order.orderId(), LectureHpTaskPurpose.HOLD_VOID)).hasSize(1);
    }

    /** 실행할 때가 된 HP 작업을 배치로 실행한다. 재시도를 기다리는 작업은 시각을 당긴다 */
    private void runDueTasks() {
        jdbcTemplate.update("update lectures.lecture_hp_task set next_attempt_at = now() - interval '10 minutes', "
                + "lease_until = case when lease_until is null then null else now() - interval '10 minutes' end "
                + "where status in ('PENDING', 'PROCESSING')");
        lectureHpTaskBatchUseCase.runDue();
    }

    /** 자동 재시도 한도를 다 쓴 것처럼 결제 처리 중인 보류를 DEAD로 둔다 */
    private void markHoldsDead(Long orderId) {
        jdbcTemplate.update("update lectures.lecture_hp_task set status = 'DEAD', last_error_code = 'UNKNOWN_RESULT' "
                + "where order_id = ? and purpose = 'ORDER_HOLD' and status = 'PENDING'", orderId);
    }

    private LectureHpTask deadHold(Long orderId) {
        return deadHolds(orderId).stream().reduce((a, b) -> {
            throw new AssertionError("DEAD 보류가 하나가 아닙니다.");
        }).orElseThrow();
    }

    private List<LectureHpTask> deadHolds(Long orderId) {
        return tasks(orderId, LectureHpTaskPurpose.ORDER_HOLD).stream()
                .filter(task -> task.getStatus() == LectureHpTaskStatus.DEAD)
                .toList();
    }

    private LectureHpTask task(Long orderId, LectureHpTaskPurpose purpose) {
        return tasks(orderId, purpose).stream().reduce((a, b) -> {
            throw new AssertionError("작업이 하나가 아닙니다. purpose=" + purpose);
        }).orElseThrow();
    }

    private List<LectureHpTask> tasks(Long orderId, LectureHpTaskPurpose purpose) {
        return lectureHpTaskRepository.findByOrderIdIn(List.of(orderId)).stream()
                .filter(task -> task.getPurpose() == purpose)
                .toList();
    }

    private List<HoldVoidCommand> voids() {
        return commands(HoldVoidCommand.class);
    }

    private List<ReleaseCommand> releases() {
        return commands(ReleaseCommand.class);
    }

    private <T> List<T> commands(Class<T> type) {
        return billing.calls().stream()
                .map(FakeBillingApiClient.Call::command)
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private BillingCallResult<?> heldInFull(Object command) {
        HoldCommand hold = (HoldCommand) command;
        return new BillingCallResult.Succeeded<>(new HoldResultDto(
                hold.referenceType(), hold.referenceId(), hold.amountHp(), false));
    }

    /** 다시 보내도 결과가 같은 지갑 4xx 불변식 위반. 보류는 바로 DEAD다 */
    private static BillingCallResult<?> invariantViolation() {
        return new BillingCallResult.Failed<>(BillingFailureCode.IDEMPOTENCY_KEY_CONFLICT,
                "같은 멱등 키로 다른 요청이 왔습니다.", 409, "IDEMPOTENCY_KEY_CONFLICT");
    }

    private static BillingCallResult<?> unknownResultAfterRetries() {
        throw BillingCallExceptions.of(BillingCallFailureKind.UNKNOWN_RESULT, 500, "HOLD_STATE_INCONSISTENT");
    }

    private static BillingCallResult<?> voided(HoldVoidCommand command, HoldVoidOutcome outcome, long releasedHp) {
        return new BillingCallResult.Succeeded<>(new HoldVoidResultDto(command.referenceType(),
                command.referenceId(), outcome, releasedHp, false));
    }

    private Lecture saveOnSale(String title, long priceHp) {
        Lecture lecture = Lecture.register(SELLER_ID, title, DESCRIPTION, priceHp, null, contentSpecs(), NOW);
        lecture.approve(ADMIN_ID, NOW);
        return lectureRepository.save(lecture);
    }
}
