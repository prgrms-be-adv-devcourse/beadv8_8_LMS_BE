package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.lecture.app.dto.OrderItemResponse;
import com.hapbang.lecture.app.dto.OrderResponse;
import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureHpTaskProperties;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskStatus;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.ObjectionStatus;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.domain.LectureObjectionCommandOutcome;
import com.hapbang.lecture.fixture.LectureOrderFixture;
import com.hapbang.lecture.fixture.ObjectionCommands;
import com.hapbang.lecture.out.LectureObjectionCommandRepository;
import com.hapbang.lecture.out.LectureHpTaskRepository;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;
import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.dto.LectureObjectionFiledDto;
import com.hapbang.shared.lecture.event.LectureObjectionRequestRefusedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.billing.RealBillingTestFixture;

/**
 * 이의 제기 Facade(LectureObjectionFacade)를 직접 호출해 DB·HP 반환(실제 billing HTTP)·명령 이력·결과 이벤트까지 확인한다 (#278).
 * 상태 규칙과 기한 경계는 LectureOrderObjectionTest가 맡는다. 명령은 고객센터가 보낸 것처럼 명령 하나가 강의 트랜잭션 하나다.
 */
@IntegrationTest
// 주문은 회원, 이의 제기 접수는 내부 API 진입점이다 (D63)
@WithMockUser(roles = {"USER", "INTERNAL"})
@RecordApplicationEvents
class LectureObjectionFacadeTest {

    private static final Long BUYER_ID = OTHER_USER_ID;
    private static final Long INQUIRY_ID = 500L;
    private static final String REASON = "강의 소개와 영상 내용이 다릅니다.";

    @Autowired
    private LectureObjectionFacade lectureObjectionFacade;

    @Autowired
    private LectureOrderFacade lectureOrderFacade;

    @Autowired
    private LectureOrderRepository lectureOrderRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private RealBillingTestFixture billing;

    @Autowired
    private LectureHpTaskBatchUseCase lectureHpTaskBatchUseCase;

    @Autowired
    private LectureHpTaskExecutor lectureHpTaskExecutor;

    @Autowired
    private LectureHpTaskRepository lectureHpTaskRepository;

    @Autowired
    private LectureHpTaskAdminFacade lectureHpTaskAdminFacade;

    @Autowired
    private EventSerializer eventSerializer;

    @Autowired
    private LectureHpTaskProperties lectureHpTaskProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(LectureConfig.CLOCK)
    private Clock clock;

    @Autowired
    private LectureObjectionCommandRepository lectureObjectionCommandRepository;

    @Autowired
    private ApplicationEvents events;

    private ObjectionCommands objections;

    @BeforeEach
    void setUp() {
        objections = new ObjectionCommands(lectureObjectionFacade);
        billing.reset();
        billing.wallet(BUYER_ID, 1_000_000L, 0L);
        billing.wallet(SELLER_ID, 0L, 0L);
    }

    @Test
    void 접수하면_FILED와_문의ID와_사유가_저장되고_구매_취소는_거부된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();

        objections.file(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.FILED);
        assertThat(item.getObjectionInquiryId()).isEqualTo(INQUIRY_ID);
        assertThat(item.getObjectionReason()).isEqualTo(REASON);
        assertThat(item.getObjectionFiledAt()).isNotNull();
        assertThatThrownBy(() -> lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.OBJECTION_FILED);
        assertThat(releases()).isEmpty();
    }

    @Test
    void 없는_항목에는_이의_제기할_수_없다() {
        assertRefused(() -> objections.file(999_999L, BUYER_ID, INQUIRY_ID, REASON),
                LectureErrorCode.OBJECTION_NOT_BUYER);
    }

    @Test
    void 지급_예정_시각이_지난_항목은_이의_제기할_수_없다() {
        LectureOrder expired = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("OBS 방송 세팅")), LocalDateTime.now(clock).minusDays(31)));
        Long orderItemId = expired.getItems().getFirst().getId();

        assertRefused(() -> objections.file(orderItemId, BUYER_ID, INQUIRY_ID, REASON),
                LectureErrorCode.OBJECTION_PERIOD_EXPIRED);
        assertThat(item(orderItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
    }

    @Test
    void 인정하면_반환_처리_중이_되고_HP_작업_배치가_반환하면_반환이_된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long orderItemId = order.items().getFirst().orderItemId();
        objections.file(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        objections.resolve(orderItemId, INQUIRY_ID, true, ADMIN_ID);

        // 판단 요청 안에서는 지갑을 부르지 않는다 (D70 (1))
        OrderItem accepted = item(orderItemId);
        assertThat(accepted.getStatus()).isEqualTo(OrderItemStatus.RETURN_PENDING);
        assertThat(accepted.getReturnedAt()).isNull();
        assertThat(accepted.getObjectionStatus()).isEqualTo(ObjectionStatus.ACCEPTED);
        assertThat(accepted.getObjectionResolvedAt()).isNotNull();
        assertThat(accepted.getObjectionResolvedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(releases()).isEmpty();

        lectureHpTaskBatchUseCase.runDue();

        assertThat(item(orderItemId)).satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURNED);
            assertThat(item.getReturnedAt()).isNotNull();
        });
        assertThat(billing.calls()).filteredOn(call -> call.command() instanceof ReleaseCommand).singleElement()
                .satisfies(call -> {
                    assertThat(call.transactionActive()).isFalse();
                    ReleaseCommand release = (ReleaseCommand) call.command();
                    assertThat(release.userId()).isEqualTo(BUYER_ID);
                    assertThat(release.referenceType()).isEqualTo(BillingReferenceType.LECTURE_ORDER_ITEM);
                    assertThat(release.referenceId()).isEqualTo(orderItemId);
                    assertThat(release.releaseRequestId()).isEqualTo("LECTURE-RETURN-" + orderItemId);
                    assertThat(release.isFull()).isTrue();
                });
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 기각하면_REJECTED로_저장되고_HP는_움직이지_않는다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        objections.file(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        objections.resolve(orderItemId, INQUIRY_ID, false, ADMIN_ID);

        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.REJECTED);
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PURCHASED);
        assertThat(item.getObjectionResolvedByUserId()).isEqualTo(ADMIN_ID);
        assertThat(releases()).isEmpty();
    }

    @Test
    void 인정_반환액이_항목_가격과_다르면_반환_처리_중에_멈추고_관리자_확인이_필요하다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        objections.file(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.resolve(orderItemId, INQUIRY_ID, true, ADMIN_ID);
        returnPreviously(orderItemId);

        lectureHpTaskBatchUseCase.runDue();

        // 결과 금액이 작업 금액과 다르면 불변식 위반이라 DEAD다. 완료 상태로 바꾸지 않는다 (D43, D57)
        assertThat(item(orderItemId).getStatus()).isEqualTo(OrderItemStatus.RETURN_PENDING);
        assertThat(lectureHpTaskRepository.findByRequestId("LECTURE-RETURN-" + orderItemId))
                .map(LectureHpTask::getStatus).hasValue(LectureHpTaskStatus.DEAD);
    }

    @Test
    void 지갑이_반환을_거부하면_반환_처리_중에_멈춘다() {
        // 강의에만 구매 완료가 기록되고 billing에는 보류가 없는 불일치 상태다.
        LectureOrder missingHold = lectureOrderRepository.save(LectureOrderFixture.order(BUYER_ID,
                List.of(saveOnSale("OBS 방송 세팅")), LocalDateTime.now(clock)));
        OrderResponse order = lectureOrderFacade.getOrder(BUYER_ID, missingHold.getId());
        Long orderItemId = order.items().getFirst().orderItemId();
        objections.file(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.resolve(orderItemId, INQUIRY_ID, true, ADMIN_ID);

        lectureHpTaskBatchUseCase.runDue();

        assertThat(item(orderItemId).getStatus()).isEqualTo(OrderItemStatus.RETURN_PENDING);
        assertThat(lectureOrderFacade.getOrder(BUYER_ID, order.orderId()).status())
                .isEqualTo(LectureOrderStatus.PAID);
    }

    @Test
    void 같은_판단_명령이_다시_오면_처음_결과를_재현하고_새_명령이면_거부되며_HP는_한_번만_반환된다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        objections.file(orderItemId, BUYER_ID, INQUIRY_ID, REASON);
        LectureObjectionResolutionRequestedEvent resolve = resolveCommand(orderItemId, true);
        assertThat(lectureObjectionFacade.resolve(resolve).succeeded()).isTrue();

        // Kafka 재전달처럼 같은 명령이 다시 온다
        LectureObjectionCommandResult replayed = lectureObjectionFacade.resolve(resolve);
        // 다른 명령 ID(운영 복구 등)의 같은 판단은 이미 판단한 항목이라 거절된다
        assertRefused(() -> objections.resolve(orderItemId, INQUIRY_ID, true, ADMIN_ID),
                LectureErrorCode.OBJECTION_NOT_FILED);
        lectureHpTaskBatchUseCase.runDue();

        assertThat(replayed.succeeded()).isTrue();
        assertThat(replayed.replayed()).isTrue();
        assertThat(releases()).hasSize(1);
        assertThat(lectureHpTaskRepository.findByRequestId("LECTURE-RETURN-" + orderItemId)).isPresent();
        // 처음 판단만 결과 이벤트를 낸다. 알림은 문의 ID로 판단 결과를 한 번만 만든다 (#818)
        assertThat(events.stream(LectureObjectionResolvedEvent.class)).singleElement()
                .isEqualTo(new LectureObjectionResolvedEvent(resolve.commandId(), INQUIRY_ID, orderItemId, true,
                        BUYER_ID, SELLER_ID, "OBS 방송 세팅", kst(item(orderItemId).getObjectionResolvedAt())));
    }

    @Test
    void 판단_결과_이벤트는_인정과_기각_모두_구매자_판매자_ID와_구매_때_강의_제목과_판단_시각을_싣는다() {
        Lecture lecture = saveOnSale("OBS 방송 세팅");
        OrderResponse order = order(lecture, saveOnSale("마이크 고르기"));
        Long acceptedItemId = order.items().getFirst().orderItemId();
        Long rejectedItemId = order.items().getLast().orderItemId();
        objections.file(acceptedItemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.file(rejectedItemId, BUYER_ID, INQUIRY_ID + 1, REASON);
        // 구매 뒤 강의 제목이 바뀌어도 알림은 구매한 제목으로 보인다
        lecture.updateInfo(SELLER_ID, "OBS 방송 세팅 (개정판)", DESCRIPTION, PRICE_HP, null, NOW);
        lectureRepository.save(lecture);

        LectureObjectionResolutionRequestedEvent accept = new LectureObjectionResolutionRequestedEvent(
                "RESOLVE:" + INQUIRY_ID, INQUIRY_ID, acceptedItemId, true, ADMIN_ID);
        LectureObjectionResolutionRequestedEvent reject = new LectureObjectionResolutionRequestedEvent(
                "RESOLVE:" + (INQUIRY_ID + 1), INQUIRY_ID + 1, rejectedItemId, false, ADMIN_ID);
        lectureObjectionFacade.resolve(accept);
        lectureObjectionFacade.resolve(reject);

        assertThat(events.stream(LectureObjectionResolvedEvent.class)).containsExactly(
                new LectureObjectionResolvedEvent(accept.commandId(), INQUIRY_ID, acceptedItemId, true, BUYER_ID,
                        SELLER_ID, "OBS 방송 세팅", kst(item(acceptedItemId).getObjectionResolvedAt())),
                new LectureObjectionResolvedEvent(reject.commandId(), INQUIRY_ID + 1, rejectedItemId, false, BUYER_ID,
                        SELLER_ID, "마이크 고르기", kst(item(rejectedItemId).getObjectionResolvedAt())));
    }

    @Test
    void 판단과_철회_결과는_명령_ID와_함께_강의_이벤트로_나가고_거절도_이벤트다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long rejectedItemId = order.items().getFirst().orderItemId();
        Long withdrawnItemId = order.items().getLast().orderItemId();
        objections.file(rejectedItemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.file(withdrawnItemId, BUYER_ID, INQUIRY_ID + 1, REASON);

        LectureObjectionResolutionRequestedEvent reject = resolveCommand(rejectedItemId, false);
        lectureObjectionFacade.resolve(reject);
        LectureObjectionWithdrawalRequestedEvent withdraw = new LectureObjectionWithdrawalRequestedEvent(
                "WITHDRAW:" + (INQUIRY_ID + 1), INQUIRY_ID + 1, withdrawnItemId, BUYER_ID);
        lectureObjectionFacade.withdraw(withdraw);
        // 이미 기각한 항목을 철회하려 한다
        LectureObjectionWithdrawalRequestedEvent late = new LectureObjectionWithdrawalRequestedEvent(
                "WITHDRAW:" + INQUIRY_ID, INQUIRY_ID, rejectedItemId, BUYER_ID);
        LectureObjectionCommandResult refused = lectureObjectionFacade.withdraw(late);

        assertThat(refused.failureCode()).isEqualTo(LectureObjectionFailureCode.OBJECTION_NOT_FILED);
        assertThat(item(rejectedItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.REJECTED);
        assertThat(item(withdrawnItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
        assertThat(events.stream(LectureObjectionResolvedEvent.class)).containsExactly(
                new LectureObjectionResolvedEvent(reject.commandId(), INQUIRY_ID, rejectedItemId, false, BUYER_ID,
                        SELLER_ID, "OBS 방송 세팅", kst(item(rejectedItemId).getObjectionResolvedAt())));
        // 항목에는 철회 시각 칸이 없어 철회 명령을 처리한 시각을 싣는다 (#818)
        LocalDateTime withdrawnAt = lectureObjectionCommandRepository.findByCommandId(withdraw.commandId())
                .orElseThrow().getProcessedAt();
        assertThat(events.stream(LectureObjectionWithdrawnEvent.class)).containsExactly(
                new LectureObjectionWithdrawnEvent(withdraw.commandId(), INQUIRY_ID + 1, withdrawnItemId, BUYER_ID,
                        SELLER_ID, "마이크 고르기", kst(withdrawnAt)));
        assertThat(events.stream(LectureObjectionRequestRefusedEvent.class)).containsExactly(
                new LectureObjectionRequestRefusedEvent(late.commandId(), INQUIRY_ID, rejectedItemId,
                        LectureObjectionCommandType.WITHDRAW, LectureObjectionFailureCode.OBJECTION_NOT_FILED));
        assertThat(lectureObjectionCommandRepository.findByCommandId(late.commandId())).hasValueSatisfying(command -> {
            assertThat(command.getOutcome()).isEqualTo(LectureObjectionCommandOutcome.REFUSED);
            assertThat(command.getFailureCode()).isEqualTo(LectureObjectionFailureCode.OBJECTION_NOT_FILED);
        });
    }

    @Test
    void 같은_접수_명령을_다시_보내면_처음_결과를_돌려주고_항목은_한_번만_바뀐다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        LectureObjectionFileRequest file = LectureObjectionFileRequest.of(INQUIRY_ID, orderItemId, BUYER_ID, REASON);

        LectureObjectionFiledDto first = lectureObjectionFacade.file(file);
        LocalDateTime filedAt = item(orderItemId).getObjectionFiledAt();
        LectureObjectionFiledDto second = lectureObjectionFacade.file(file);

        assertThat(second).isEqualTo(first).isEqualTo(new LectureObjectionFiledDto("FILE:" + INQUIRY_ID, INQUIRY_ID,
                orderItemId));
        assertThat(item(orderItemId).getObjectionFiledAt()).isEqualTo(filedAt);
        assertThat(lectureObjectionCommandRepository.findAll()).singleElement().satisfies(command -> {
            assertThat(command.getCommandId()).isEqualTo("FILE:" + INQUIRY_ID);
            assertThat(command.getCommandType()).isEqualTo(LectureObjectionCommandType.FILE);
            assertThat(command.getOutcome()).isEqualTo(LectureObjectionCommandOutcome.SUCCEEDED);
        });
    }

    @Test
    void 거절한_접수_명령은_다시_보내도_같은_사유로_거절되고_이력은_하나다() {
        LectureObjectionFileRequest file = LectureObjectionFileRequest.of(INQUIRY_ID, 999_999L, BUYER_ID, REASON);

        assertRefused(() -> lectureObjectionFacade.file(file), LectureErrorCode.OBJECTION_NOT_BUYER);
        assertRefused(() -> lectureObjectionFacade.file(file), LectureErrorCode.OBJECTION_NOT_BUYER);

        assertThat(lectureObjectionCommandRepository.findAll()).singleElement().satisfies(command -> {
            assertThat(command.getOutcome()).isEqualTo(LectureObjectionCommandOutcome.REFUSED);
            assertThat(command.getFailureCode()).isEqualTo(LectureObjectionFailureCode.OBJECTION_NOT_BUYER);
        });
    }

    @Test
    void 같은_명령_ID로_다른_내용이_오면_충돌이고_처음_결과는_그대로다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        lectureObjectionFacade.file(LectureObjectionFileRequest.of(INQUIRY_ID, orderItemId, BUYER_ID, REASON));
        LectureObjectionResolutionRequestedEvent reject = resolveCommand(orderItemId, false);
        lectureObjectionFacade.resolve(reject);

        assertRefused(() -> lectureObjectionFacade.file(LectureObjectionFileRequest.of(INQUIRY_ID, orderItemId,
                BUYER_ID, "다른 사유")), LectureErrorCode.OBJECTION_COMMAND_CONFLICT);
        LectureObjectionCommandResult conflict = lectureObjectionFacade.resolve(resolveCommand(orderItemId, true));

        assertThat(conflict.failureCode()).isEqualTo(LectureObjectionFailureCode.OBJECTION_COMMAND_CONFLICT);
        assertThat(item(orderItemId).getObjectionStatus()).isEqualTo(ObjectionStatus.REJECTED);
        assertThat(item(orderItemId).getObjectionReason()).isEqualTo(REASON);
        assertThat(lectureObjectionCommandRepository.findAll()).hasSize(2);
        // 충돌은 기록하지 않고 거절 이벤트만 낸다. 고객센터는 처리 중인 명령이 아니면 무시한다
        assertThat(events.stream(LectureObjectionRequestRefusedEvent.class)).singleElement()
                .extracting(LectureObjectionRequestRefusedEvent::failureCode)
                .isEqualTo(LectureObjectionFailureCode.OBJECTION_COMMAND_CONFLICT);
    }

    @Test
    void 인정_반환이_끝나면_반환_완료_이벤트가_멈추면_조치_필요_이벤트가_나간다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long completedItemId = order.items().getFirst().orderItemId();
        Long deadItemId = order.items().getLast().orderItemId();
        objections.file(completedItemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.file(deadItemId, BUYER_ID, INQUIRY_ID + 1, REASON);
        objections.resolve(completedItemId, INQUIRY_ID, true, ADMIN_ID);
        objections.resolve(deadItemId, INQUIRY_ID + 1, true, ADMIN_ID);

        lectureHpTaskExecutor.execute(returnTaskId(completedItemId));
        // 다른 요청으로 일부 HP가 이미 반환되어 실제 남은 반환액이 작업 금액보다 작다 (D43)
        returnPreviously(deadItemId);
        lectureHpTaskExecutor.execute(returnTaskId(deadItemId));

        assertThat(events.stream(LectureObjectionReturnCompletedEvent.class)).containsExactly(
                new LectureObjectionReturnCompletedEvent(INQUIRY_ID, completedItemId, BUYER_ID, SELLER_ID,
                        "OBS 방송 세팅", kst(item(completedItemId).getReturnedAt())));
        // 발생 시각은 작업을 DEAD로 바꾼 시각이다
        assertThat(events.stream(LectureObjectionReturnActionRequiredEvent.class)).containsExactly(
                new LectureObjectionReturnActionRequiredEvent(INQUIRY_ID + 1, deadItemId, BUYER_ID, SELLER_ID,
                        "마이크 고르기", 1, kst(returnTask(deadItemId).getLastFailedAt())));
    }

    @Test
    @WithMockUser(roles = {"USER", "INTERNAL", "ADMIN"})
    void 반환이_다시_멈추면_반환_지연_회차가_1씩_늘고_다른_문의는_따로_센다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        Long itemId = order.items().getFirst().orderItemId();
        Long otherItemId = order.items().getLast().orderItemId();
        objections.file(itemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.file(otherItemId, BUYER_ID, INQUIRY_ID + 1, REASON);
        objections.resolve(itemId, INQUIRY_ID, true, ADMIN_ID);
        objections.resolve(otherItemId, INQUIRY_ID + 1, true, ADMIN_ID);
        // 다른 요청으로 일부 HP가 이미 반환되어 실제 남은 반환액이 작업 금액보다 작다 (D43)
        returnPreviously(itemId);
        returnPreviously(otherItemId);

        lectureHpTaskExecutor.execute(returnTaskId(itemId));
        // 관리자가 원인을 확인하지 못한 채 다시 시도해 또 멈춘다 (D57 (9))
        lectureHpTaskAdminFacade.retry(ADMIN_ID, returnTaskId(itemId));
        lectureHpTaskExecutor.execute(returnTaskId(itemId));
        lectureHpTaskExecutor.execute(returnTaskId(otherItemId));

        assertThat(events.stream(LectureObjectionReturnActionRequiredEvent.class))
                .extracting(LectureObjectionReturnActionRequiredEvent::inquiryId,
                        LectureObjectionReturnActionRequiredEvent::returnDelaySequence)
                .containsExactly(tuple(INQUIRY_ID, 1), tuple(INQUIRY_ID, 2), tuple(INQUIRY_ID + 1, 1));
        assertThat(item(itemId).getReturnDelayCount()).isEqualTo(2);
        assertThat(item(otherItemId).getReturnDelayCount()).isEqualTo(1);
    }

    /**
     * 재발행 배치는 발행 기록(outbox)에 저장한 JSON을 이 앱의 Modulith 직렬화기로 다시 읽어 보낸다(LectureEventRepublishUseCase).
     * 회차는 발행할 때 JSON에 저장되므로 항목의 회차가 그 뒤 바뀌어도 다시 계산하지 않는다. #818 배포 전에 저장된 옛 형식도 읽고,
     * 새 필드는 null이 된다(새 필드가 primitive면 Jackson 3 기본값에서 읽지 못한다)
     */
    @Test
    @WithMockUser(roles = {"USER", "INTERNAL", "ADMIN"})
    void 발행_기록에서_다시_읽은_반환_지연은_발행할_때의_회차이고_옛_형식은_새_필드가_null이다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long itemId = order.items().getFirst().orderItemId();
        objections.file(itemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.resolve(itemId, INQUIRY_ID, true, ADMIN_ID);
        returnPreviously(itemId);
        lectureHpTaskExecutor.execute(returnTaskId(itemId));
        LectureObjectionReturnActionRequiredEvent first = events.stream(LectureObjectionReturnActionRequiredEvent.class)
                .findFirst().orElseThrow();
        Object stored = eventSerializer.serialize(first);

        lectureHpTaskAdminFacade.retry(ADMIN_ID, returnTaskId(itemId));
        lectureHpTaskExecutor.execute(returnTaskId(itemId));

        assertThat(item(itemId).getReturnDelayCount()).isEqualTo(2);
        assertThat(eventSerializer.deserialize(stored, LectureObjectionReturnActionRequiredEvent.class))
                .isEqualTo(first)
                .extracting(LectureObjectionReturnActionRequiredEvent::returnDelaySequence).isEqualTo(1);
        assertThat(eventSerializer.deserialize("{\"inquiryId\":500,\"orderItemId\":10}",
                LectureObjectionReturnActionRequiredEvent.class))
                .isEqualTo(new LectureObjectionReturnActionRequiredEvent(500L, 10L, null, null, null, null, null));
    }

    /**
     * DEAD가 되는 다른 경로: 지갑을 부른 뒤 결과를 기록하지 못한 채 선점이 만료됐고 자동 재시도 한도에 닿았다 (D57 (3)).
     * 선점할 때 조건부 UPDATE로 DEAD로 닫고 같은 트랜잭션에서 결과 처리기를 부른다. 영속성 컨텍스트가 비워진 뒤라 항목을 새로 읽는다
     */
    @Test
    void 한도에_닿은_채_선점이_만료돼_멈춘_반환도_회차를_올리고_반환_지연을_한_번만_낸다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long itemId = order.items().getFirst().orderItemId();
        objections.file(itemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.resolve(itemId, INQUIRY_ID, true, ADMIN_ID);
        Long taskId = returnTaskId(itemId);
        LocalDateTime expired = LocalDateTime.now(clock).minusMinutes(10);
        jdbcTemplate.update("""
                update lectures.lecture_hp_task
                set status = 'PROCESSING', claim_token = ?, lease_until = ?, attempt_count = ?
                where id = ?""", UUID.randomUUID(), expired, lectureHpTaskProperties.retryMaxAttempts(), taskId);

        lectureHpTaskExecutor.execute(taskId);
        // 이미 DEAD라 선점도 조건부 UPDATE도 되지 않는다
        lectureHpTaskExecutor.execute(taskId);

        LectureHpTask task = returnTask(itemId);
        assertThat(task.getStatus()).isEqualTo(LectureHpTaskStatus.DEAD);
        assertThat(task.getLastErrorCode()).isEqualTo(LectureHpTaskExecutor.LEASE_EXPIRED);
        assertThat(releases()).isEmpty();
        assertThat(item(itemId).getReturnDelayCount()).isEqualTo(1);
        assertThat(events.stream(LectureObjectionReturnActionRequiredEvent.class)).containsExactly(
                new LectureObjectionReturnActionRequiredEvent(INQUIRY_ID, itemId, BUYER_ID, SELLER_ID, "OBS 방송 세팅", 1,
                        kst(task.getLastFailedAt())));
    }

    @Test
    void 멈춘_반환_작업을_다시_실행해도_반환_지연_이벤트는_다시_나가지_않고_회차도_그대로다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long itemId = order.items().getFirst().orderItemId();
        objections.file(itemId, BUYER_ID, INQUIRY_ID, REASON);
        objections.resolve(itemId, INQUIRY_ID, true, ADMIN_ID);
        returnPreviously(itemId);
        lectureHpTaskExecutor.execute(returnTaskId(itemId));

        // DEAD 작업은 선점되지 않아 결과 처리기(onDead)가 다시 불리지 않는다
        lectureHpTaskExecutor.execute(returnTaskId(itemId));
        lectureHpTaskBatchUseCase.runDue();

        assertThat(returnTask(itemId).getStatus()).isEqualTo(LectureHpTaskStatus.DEAD);
        assertThat(events.stream(LectureObjectionReturnActionRequiredEvent.class)).singleElement()
                .extracting(LectureObjectionReturnActionRequiredEvent::returnDelaySequence).isEqualTo(1);
        assertThat(item(itemId).getReturnDelayCount()).isEqualTo(1);
    }

    @Test
    void 없는_항목과_다른_문의로는_판단할_수_없다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        objections.file(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        assertRefused(() -> objections.resolve(999_999L, INQUIRY_ID, true, ADMIN_ID),
                LectureErrorCode.OBJECTION_NOT_FILED);
        assertRefused(() -> objections.resolve(999_999L, INQUIRY_ID, false, ADMIN_ID),
                LectureErrorCode.OBJECTION_NOT_FILED);
        assertRefused(() -> objections.resolve(orderItemId, 501L, false, ADMIN_ID),
                LectureErrorCode.OBJECTION_INQUIRY_MISMATCH);
        assertRefused(() -> objections.resolve(orderItemId, 501L, true, ADMIN_ID),
                LectureErrorCode.OBJECTION_INQUIRY_MISMATCH);
        assertFiledAndPurchased(orderItemId);
    }

    @Test
    void 같은_주문의_마지막_두_항목의_반환이_동시에_끝나도_주문이_취소된다() throws Exception {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"), saveOnSale("마이크 고르기"));
        List<Long> itemIds = order.items().stream().map(OrderItemResponse::orderItemId).toList();
        for (Long itemId : itemIds) {
            objections.file(itemId, BUYER_ID, INQUIRY_ID + itemId, REASON);
            objections.resolve(itemId, INQUIRY_ID + itemId, true, ADMIN_ID);
        }
        // 두 반환이 모두 지갑 호출에 들어갈 때까지 기다리게 해, 결과 기록이 주문을 잠그지 않으면 서로의 반환을 보지 못하게 한다
        CyclicBarrier bothReleasing = new CyclicBarrier(2);
        billing.duringNextCall(() -> await(bothReleasing));
        billing.duringNextCall(() -> await(bothReleasing));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = itemIds.stream()
                    .map(itemId -> lectureHpTaskRepository.findByRequestId("LECTURE-RETURN-" + itemId)
                            .orElseThrow().getId())
                    .<Future<?>>map(taskId -> executor.submit(() -> lectureHpTaskExecutor.execute(taskId)))
                    .toList();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        OrderResponse saved = lectureOrderFacade.getOrder(BUYER_ID, order.orderId());
        assertThat(saved.items()).extracting(OrderItemResponse::status).containsOnly(OrderItemStatus.RETURNED);
        assertThat(saved.status()).isEqualTo(LectureOrderStatus.CANCELED);
        assertThat(releases()).hasSize(2);
    }

    @Test
    void 철회하면_NONE으로_저장되고_구매_취소하면_보류_HP를_반환한다() {
        OrderResponse order = order(saveOnSale("OBS 방송 세팅"));
        Long orderItemId = order.items().getFirst().orderItemId();
        objections.file(orderItemId, BUYER_ID, INQUIRY_ID, REASON);

        objections.withdraw(orderItemId, BUYER_ID, INQUIRY_ID);

        OrderItem item = item(orderItemId);
        assertThat(item.getObjectionStatus()).isEqualTo(ObjectionStatus.NONE);
        assertThat(item.getObjectionInquiryId()).isNull();
        assertThat(item.getObjectionReason()).isNull();
        assertThat(item.getObjectionFiledAt()).isNull();
        assertThat(releases()).isEmpty();

        lectureOrderFacade.cancel(BUYER_ID, order.orderId(), orderItemId);

        assertThat(item(orderItemId).getStatus()).isEqualTo(OrderItemStatus.CANCELED);
        assertThat(releases()).singleElement()
                .extracting(ReleaseCommand::referenceId).isEqualTo(orderItemId);
    }

    @Test
    void 없는_항목은_철회할_수_없다() {
        assertRefused(() -> objections.withdraw(999_999L, BUYER_ID, INQUIRY_ID),
                LectureErrorCode.OBJECTION_NOT_BUYER);
    }

    private static LectureObjectionResolutionRequestedEvent resolveCommand(Long orderItemId, boolean accepted) {
        return new LectureObjectionResolutionRequestedEvent("RESOLVE:" + INQUIRY_ID, INQUIRY_ID, orderItemId, accepted,
                ADMIN_ID);
    }

    private void returnPreviously(Long orderItemId) {
        assertThat(billing.client().release(ReleaseCommand.partial(BUYER_ID,
                BillingReferenceType.LECTURE_ORDER_ITEM, orderItemId, "PREEXISTING-RETURN-" + orderItemId, 1_000L)))
                .isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();
    }

    private Long returnTaskId(Long orderItemId) {
        return returnTask(orderItemId).getId();
    }

    private LectureHpTask returnTask(Long orderItemId) {
        return lectureHpTaskRepository.findByRequestId("LECTURE-RETURN-" + orderItemId).orElseThrow();
    }

    /** 강의 시각(KST)을 이벤트에 싣는 offset 시각으로 바꾼다 */
    private static OffsetDateTime kst(LocalDateTime time) {
        return time.atZone(LectureConfig.ZONE).toOffsetDateTime();
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

    private List<ReleaseCommand> releases() {
        return billing.calls().stream()
                .map(RealBillingTestFixture.Call::command)
                .filter(ReleaseCommand.class::isInstance)
                .map(ReleaseCommand.class::cast)
                .toList();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (BrokenBarrierException | TimeoutException e) {
            // 한쪽만 들어오면(직렬로 실행된 경우) 기다리지 않고 진행한다
        }
    }

    private static void assertRefused(Runnable action, LectureErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
