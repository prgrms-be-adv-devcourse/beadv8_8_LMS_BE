package com.hapbang.lecture.fake;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.global.exception.BaseErrorCode;
import com.hapbang.global.exception.BusinessException;
import com.hapbang.shared.billing.dto.LectureSettlementCommand;
import com.hapbang.shared.billing.dto.LectureSettlementResultDto;
import com.hapbang.shared.billing.port.LectureSettlementPort;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 강의 모듈 테스트용 판매자 지급 예정 등록 (#182). 모듈 테스트 컨텍스트에는 billing 빈이 없으므로 shared {@link LectureSettlementPort}를
 * 이 Fake로 채운다.
 * <ul>
 *     <li>지급 가능 시각은 billing과 같은 규칙(구매 시각의 KST 날짜 + 31일 00:00 KST)으로 계산한다.</li>
 *     <li>실제 billing처럼 호출한 쪽 트랜잭션을 따른다. 커밋된 등록·취소만 기록하고, 롤백되면 되돌린다.</li>
 *     <li>{@link #failSchedulesWith}, {@link #failCancelsWith}로 billing 거절(예: 판매자 지갑 없음)을 흉내 낸다.</li>
 * </ul>
 * 컨텍스트가 테스트 사이에 재사용되므로 테스트마다 {@link #reset()}한다.
 */
@Component
public class FakeLectureSettlementPort implements LectureSettlementPort {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Map<Long, LectureSettlementCommand> scheduled = new ConcurrentHashMap<>();
    private final List<LectureSettlementCommand> schedules = new CopyOnWriteArrayList<>();
    private final List<Long> cancels = new CopyOnWriteArrayList<>();
    private volatile FakeSettlementErrorCode scheduleFailure;
    private volatile FakeSettlementErrorCode cancelFailure;

    public void reset() {
        scheduled.clear();
        schedules.clear();
        cancels.clear();
        scheduleFailure = null;
        cancelFailure = null;
    }

    /** 다음 등록부터 billing이 이 오류로 거절한다 */
    public void failSchedulesWith(FakeSettlementErrorCode errorCode) {
        this.scheduleFailure = errorCode;
    }

    /** 다음 취소부터 billing이 이 오류로 거절한다 */
    public void failCancelsWith(FakeSettlementErrorCode errorCode) {
        this.cancelFailure = errorCode;
    }

    /** 커밋된 지급 예정 등록 */
    public List<LectureSettlementCommand> schedules() {
        return List.copyOf(schedules);
    }

    /** 커밋된 지급 예정 취소(주문 항목 ID) */
    public List<Long> cancels() {
        return List.copyOf(cancels);
    }

    @Override
    public LectureSettlementResultDto schedule(LectureSettlementCommand command) {
        if (scheduleFailure != null) {
            throw new BusinessException(scheduleFailure);
        }
        scheduled.put(command.orderItemId(), command);
        onCompletion(() -> schedules.add(command), () -> scheduled.remove(command.orderItemId()));
        return new LectureSettlementResultDto(command.orderItemId(), command.sellerUserId(), command.amountHp(),
                payableAt(command.purchasedAt()), false);
    }

    @Override
    public LectureSettlementResultDto cancel(Long orderItemId) {
        if (cancelFailure != null) {
            throw new BusinessException(cancelFailure);
        }
        LectureSettlementCommand command = scheduled.get(orderItemId);
        if (command == null) {
            throw new BusinessException(FakeSettlementErrorCode.SETTLEMENT_NOT_FOUND);
        }
        onCompletion(() -> cancels.add(orderItemId), () -> {
        });
        return new LectureSettlementResultDto(orderItemId, command.sellerUserId(), command.amountHp(),
                payableAt(command.purchasedAt()), false);
    }

    private static Instant payableAt(Instant purchasedAt) {
        return purchasedAt.atZone(KST).toLocalDate().plusDays(31).atStartOfDay(KST).toInstant();
    }

    private static void onCompletion(Runnable afterCommit, Runnable afterRollback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            afterCommit.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    afterCommit.run();
                } else {
                    afterRollback.run();
                }
            }
        });
    }

    @Getter
    @RequiredArgsConstructor
    public enum FakeSettlementErrorCode implements BaseErrorCode {
        WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
        SETTLEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "지급 건을 찾을 수 없습니다."),
        SETTLEMENT_INVALID_STATE(HttpStatus.CONFLICT, "지급 건의 현재 상태에서는 요청한 처리를 할 수 없습니다.");

        private final HttpStatus status;
        private final String message;
    }
}
