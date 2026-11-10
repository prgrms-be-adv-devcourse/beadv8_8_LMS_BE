package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureObjectionCommand;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureObjectionCommandRepository;
import com.hapbang.shared.customersupport.event.LectureObjectionResolutionRequestedEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionWithdrawalRequestedEvent;
import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.event.LectureObjectionRequestRefusedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * 고객센터의 이의 제기 명령(접수·판단·철회)을 명령 ID마다 한 번만 처리한다 (#278, D60 (3)·D71).
 * <ul>
 *     <li>명령 하나 = 강의 트랜잭션 하나. 항목 변경, 명령 이력({@link LectureObjectionCommand}), 결과 이벤트를 함께 커밋한다.</li>
 *     <li>같은 명령 ID·같은 내용이 다시 오면 처음 결과를 재현한다. 항목도 이벤트도 다시 만들지 않는다.
 *     같은 명령 ID·다른 내용이면 이력을 바꾸지 않고 {@link LectureObjectionFailureCode#OBJECTION_COMMAND_CONFLICT}로 거절한다.</li>
 *     <li>업무 거절({@code OBJECTION_*})은 예외가 아니라 결과다. 거절도 이력에 남겨 다시 와도 같은 사유를 낸다.
 *     판단·철회의 결과는 강의 이벤트({@code lecture.events.v1})로 고객센터·알림에 알린다({@link LectureObjectionEvents}).</li>
 *     <li>낙관적 락 충돌·같은 명령의 동시 처리(명령 ID 유일 제약)·기술 실패는 예외다. 트랜잭션이 롤백되고, 호출한 쪽이
 *     같은 명령을 다시 보내면(HTTP 재시도, Kafka 재시도) 처음부터 다시 처리하거나 먼저 끝난 결과를 재현한다.</li>
 * </ul>
 * 항목 규칙은 그대로 {@code OrderObjection*UseCase}가 맡는다. 이 UseCase들은 트랜잭션을 열지 않고 상태를 바꾸기 전에 거절하므로,
 * 거절을 잡아도 이 트랜잭션은 커밋할 수 있다.
 */
@Slf4j
@Service
public class LectureObjectionCommandUseCase {

    private final LectureObjectionCommandRepository lectureObjectionCommandRepository;
    private final OrderObjectionFileUseCase orderObjectionFileUseCase;
    private final OrderObjectionResolveUseCase orderObjectionResolveUseCase;
    private final OrderObjectionWithdrawUseCase orderObjectionWithdrawUseCase;
    private final LectureObjectionEvents lectureObjectionEvents;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public LectureObjectionCommandUseCase(LectureObjectionCommandRepository lectureObjectionCommandRepository,
                                          OrderObjectionFileUseCase orderObjectionFileUseCase,
                                          OrderObjectionResolveUseCase orderObjectionResolveUseCase,
                                          OrderObjectionWithdrawUseCase orderObjectionWithdrawUseCase,
                                          LectureObjectionEvents lectureObjectionEvents,
                                          ApplicationEventPublisher eventPublisher,
                                          @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureObjectionCommandRepository = lectureObjectionCommandRepository;
        this.orderObjectionFileUseCase = orderObjectionFileUseCase;
        this.orderObjectionResolveUseCase = orderObjectionResolveUseCase;
        this.orderObjectionWithdrawUseCase = orderObjectionWithdrawUseCase;
        this.lectureObjectionEvents = lectureObjectionEvents;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** 접수 (내부 HTTP). 결과는 응답으로 돌려주고 이벤트는 내지 않는다 */
    @Transactional
    public LectureObjectionCommandResult file(LectureObjectionFileRequest request) {
        String payloadHash = LectureObjectionCommand.fingerprint(LectureObjectionCommandType.FILE, request.inquiryId(),
                request.orderItemId(), request.buyerUserId(), request.reason());
        return process(request.commandId(), LectureObjectionCommandType.FILE, request.inquiryId(), request.orderItemId(),
                payloadHash, () -> orderObjectionFileUseCase.fileObjection(request.orderItemId(), request.buyerUserId(),
                        request.inquiryId(), request.reason())).result();
    }

    /** 판단 (고객센터 이벤트). 결과를 강의 이벤트로 낸다 */
    @Transactional
    public LectureObjectionCommandResult resolve(LectureObjectionResolutionRequestedEvent command) {
        String payloadHash = LectureObjectionCommand.fingerprint(LectureObjectionCommandType.RESOLVE,
                command.inquiryId(), command.orderItemId(), command.accepted(), command.adminUserId());
        Processed processed = process(command.commandId(), LectureObjectionCommandType.RESOLVE,
                command.inquiryId(), command.orderItemId(), payloadHash,
                () -> orderObjectionResolveUseCase.resolveObjection(command.orderItemId(), command.inquiryId(),
                        command.accepted(), command.adminUserId()));
        LectureObjectionCommandResult result = processed.result();
        if (processed.applied()) {
            lectureObjectionEvents.resolved(result.commandId(), result.inquiryId(), command.accepted(),
                    processed.item());
        } else if (!result.replayed()) {
            eventPublisher.publishEvent(refused(result));
        }
        return result;
    }

    /** 철회 (고객센터 이벤트). 결과를 강의 이벤트로 낸다 */
    @Transactional
    public LectureObjectionCommandResult withdraw(LectureObjectionWithdrawalRequestedEvent command) {
        String payloadHash = LectureObjectionCommand.fingerprint(LectureObjectionCommandType.WITHDRAW,
                command.inquiryId(), command.orderItemId(), command.buyerUserId());
        Processed processed = process(command.commandId(), LectureObjectionCommandType.WITHDRAW,
                command.inquiryId(), command.orderItemId(), payloadHash,
                () -> orderObjectionWithdrawUseCase.withdrawObjection(command.orderItemId(), command.buyerUserId(),
                        command.inquiryId()));
        LectureObjectionCommandResult result = processed.result();
        if (processed.applied()) {
            lectureObjectionEvents.withdrawn(result.commandId(), result.inquiryId(), processed.item(),
                    processed.processedAt());
        } else if (!result.replayed()) {
            eventPublisher.publishEvent(refused(result));
        }
        return result;
    }

    private Processed process(String commandId, LectureObjectionCommandType commandType, Long inquiryId,
                              Long orderItemId, String payloadHash, Supplier<OrderItem> apply) {
        Optional<LectureObjectionCommand> processed = lectureObjectionCommandRepository.findByCommandId(commandId);
        if (processed.isPresent()) {
            LectureObjectionCommand command = processed.get();
            if (!command.hasPayload(payloadHash)) {
                log.error("같은 명령 ID로 다른 내용의 이의 제기 명령이 왔다 commandId={}, commandType={}, inquiryId={}, "
                        + "orderItemId={}", commandId, commandType, inquiryId, orderItemId);
                return Processed.notApplied(new LectureObjectionCommandResult(commandId, commandType, inquiryId,
                        orderItemId, LectureObjectionFailureCode.OBJECTION_COMMAND_CONFLICT, false));
            }
            log.info("이미 처리한 이의 제기 명령이라 처음 결과를 재현한다 commandId={}, outcome={}", commandId,
                    command.getOutcome());
            return Processed.notApplied(new LectureObjectionCommandResult(commandId, commandType, inquiryId,
                    orderItemId, command.getFailureCode(), true));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LectureObjectionFailureCode failureCode = null;
        OrderItem item = null;
        try {
            item = apply.get();
        } catch (LectureException e) {
            // OBJECTION_* 외의 업무 예외는 이 명령의 계약에 없는 실패라 그대로 던진다
            failureCode = LectureObjectionFailureCode.from(e.getErrorCode().getCode()).orElseThrow(() -> e);
        }
        LectureObjectionCommand command = failureCode == null
                ? LectureObjectionCommand.succeeded(commandId, commandType, inquiryId, orderItemId, payloadHash, now)
                : LectureObjectionCommand.refused(commandId, commandType, inquiryId, orderItemId, payloadHash,
                failureCode, now);
        lectureObjectionCommandRepository.saveAndFlush(command);
        return new Processed(new LectureObjectionCommandResult(commandId, commandType, inquiryId, orderItemId,
                failureCode, false), item, now);
    }

    /**
     * 명령 처리 결과와, 이번에 반영했으면 바뀐 항목·처리 시각(명령 이력의 처리 시각).
     * 재현·충돌이면 처리하지 않아 둘 다 없고, 거절이면 항목을 바꾸지 않아 항목이 없다
     */
    private record Processed(LectureObjectionCommandResult result, OrderItem item, LocalDateTime processedAt) {

        static Processed notApplied(LectureObjectionCommandResult result) {
            return new Processed(result, null, null);
        }

        /** 이번에 항목에 반영했다 (결과 이벤트를 낼 때) */
        boolean applied() {
            return !result.replayed() && result.succeeded();
        }
    }

    private static LectureObjectionRequestRefusedEvent refused(LectureObjectionCommandResult result) {
        return new LectureObjectionRequestRefusedEvent(result.commandId(), result.inquiryId(), result.orderItemId(),
                result.commandType(), result.failureCode());
    }
}
