package com.hapbang.billing.app;

import java.util.Optional;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.HoldVoid;
import com.hapbang.billing.domain.PointHold;
import com.hapbang.billing.out.HoldVoidRepository;
import com.hapbang.billing.out.PointHoldRepository;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;

import lombok.RequiredArgsConstructor;

// 보류 무효화 (#479, docs/billing/CONTEXT.md 6장). 호출한 모듈(강의·출연)이 참조 대상의 보류를 더는 쓰지 않을 때 부른다.
// - 보류가 있고 남은 HP가 있으면 전부 반환한다(기존 반환 경로, 반환 요청 ID VOID, 원장 RELEASE) → RELEASED
// - 보류가 있지만 이미 끝났으면(전부 반환·지급) 바꾸지 않는다 → ALREADY_CLOSED
// - 보류가 없으면 무효 표시만 남긴다 → VOIDED
// 어느 경우든 무효화 기록을 남기고, 기록이 있는 참조 대상에는 보류를 만들지 않는다(HoldCreateUseCase가 HOLD_VOIDED로 거절).
// 참조 대상당 한 번이며 같은 요청을 다시 보내면 처음 결과를 돌려준다(replayed). 결제 회원의 지갑 락 아래에서 처리해 같은 회원의
// 보류와 직렬화된다: 보류가 먼저면 무효화가 그 보류를 반환하고, 무효화가 먼저면 보류가 HOLD_VOIDED로 거절된다.
@Service
@RequiredArgsConstructor
public class HoldVoidUseCase {

    /** 무효화가 남은 보류를 반환할 때 쓰는 반환 요청 ID. 참조 대상당 무효화는 한 번이라 고정 값이다 */
    static final String VOID_RELEASE_REQUEST_ID = "VOID";
    static final String HOLD_VOID_REFERENCE_UNIQUE = "uk_hold_void_reference";
    static final String UNIQUE_VIOLATION = "23505";

    private final HoldProcessing holdProcessing;
    private final LedgerRecordUseCase ledgerRecordUseCase;
    private final PointHoldRepository pointHoldRepository;
    private final HoldVoidRepository holdVoidRepository;
    private final HoldReleaseUseCase holdReleaseUseCase;

    @Transactional
    public HoldVoidResult voidHold(HoldVoidCommand command) {
        Long walletId = holdProcessing.walletIdOf(command.userId());
        // 보류(HoldCreateUseCase)와 같은 지갑 락으로 직렬화한다. 락 뒤의 조회는 먼저 커밋된 보류·무효화 기록을 본다.
        ledgerRecordUseCase.lockWallet(walletId);

        Optional<HoldVoid> existing = holdVoidRepository.findByReferenceTypeAndReferenceId(command.referenceType(),
                command.referenceId());
        if (existing.isPresent()) {
            if (!existing.get().getUserId().equals(command.userId())) {
                throw new BillingException(BillingErrorCode.HOLD_OWNER_MISMATCH);
            }
            return HoldVoidResult.of(existing.get(), true);
        }

        Optional<PointHold> hold = pointHoldRepository.findByIdempotencyKey(
                BillingKeys.hold(command.referenceType(), command.referenceId()));
        HoldVoidOutcome outcome;
        long releasedHp = 0L;
        if (hold.isEmpty()) {
            outcome = HoldVoidOutcome.VOIDED;
        } else {
            // 소유 확인과 보류 락(지갑 → 보류 순서). 다른 회원의 보류면 HOLD_OWNER_MISMATCH다.
            PointHold locked = holdProcessing.lockHold(hold.get().getIdempotencyKey(), walletId);
            if (locked.isOpen() && locked.heldTotalHp() > 0) {
                releasedHp = holdReleaseUseCase.release(ReleaseCommand.full(command.userId(), command.referenceType(),
                        command.referenceId(), VOID_RELEASE_REQUEST_ID)).releasedHp();
                outcome = HoldVoidOutcome.RELEASED;
            } else {
                outcome = HoldVoidOutcome.ALREADY_CLOSED;
            }
        }
        return HoldVoidResult.of(save(HoldVoid.of(command.userId(), walletId, command.referenceType(),
                command.referenceId(), outcome, releasedHp)), false);
    }

    // 무효화 기록을 저장한다. 다른 회원이 같은 참조 대상을 동시에 무효화해 참조 대상 UNIQUE가 충돌하면(같은 회원은 지갑 락으로
    // 직렬화된다) 다른 회원의 참조 대상이므로 HOLD_OWNER_MISMATCH로 바꿔 전체를 롤백시킨다.
    private HoldVoid save(HoldVoid holdVoid) {
        try {
            return holdVoidRepository.saveAndFlush(holdVoid);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateReference(exception)) {
                throw new BillingException(BillingErrorCode.HOLD_OWNER_MISMATCH, exception);
            }
            throw exception;
        }
    }

    // 예외 원인의 PostgreSQL 오류가 UNIQUE 위반(23505)이고 제약 이름이 참조 대상 UNIQUE인지 확인한다(서버 언어와 무관).
    static boolean isDuplicateReference(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && HOLD_VOID_REFERENCE_UNIQUE.equals(serverError.getConstraint());
            }
        }
        return false;
    }
}
