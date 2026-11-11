package com.hapbang.lecture.domain;

import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.out.BillingCallFailureKind;

/**
 * 지갑 실패를 HP 작업 유형별로 나눈다 (D57 (5)). 같은 코드라도 작업에 따라 뜻이 다르다
 * (예: HOLD의 {@code INSUFFICIENT_BALANCE}는 정상 거절이지만 RELEASE에서는 나올 수 없다).
 */
public final class LectureHpTaskClassifier {

    private LectureHpTaskClassifier() {
    }

    /** 지갑이 실패로 응답한 경우. 표에 없는 코드(처음 보는 {@code UNMAPPED} 포함)는 불변식 위반이다 */
    public static LectureHpAttemptResult classify(LectureHpTaskType taskType, BillingFailureCode code) {
        if (code == BillingFailureCode.WALLET_NOT_FOUND) {
            return LectureHpAttemptResult.RETRYABLE;
        }
        return switch (taskType) {
            // 보류 무효화된 대상(D73 (5))과 탈퇴로 닫힌 지갑(D76)은 다시 보내도 보류가 생기지 않는다
            case HOLD -> code == BillingFailureCode.INSUFFICIENT_BALANCE || code == BillingFailureCode.HOLD_VOIDED
                    || code == BillingFailureCode.WALLET_CLOSED
                    ? LectureHpAttemptResult.TERMINAL_REJECTION : LectureHpAttemptResult.INVARIANT_VIOLATION;
            // 무효화의 다른 회원 보류(HOLD_OWNER_MISMATCH)는 다시 보내도 같아 운영 확인 대상이다
            case RELEASE, VOID -> LectureHpAttemptResult.INVARIANT_VIOLATION;
            case PAYOUT -> code == BillingFailureCode.PAYOUT_ALREADY_SETTLED
                    ? LectureHpAttemptResult.TERMINAL_REJECTION : LectureHpAttemptResult.INVARIANT_VIOLATION;
        };
    }

    /** 지갑의 처리 결과를 얻지 못한 경우. 종류를 그대로 따른다 (D64) */
    public static LectureHpAttemptResult classify(BillingCallFailureKind kind) {
        return switch (kind) {
            case RETRYABLE -> LectureHpAttemptResult.RETRYABLE;
            case UNKNOWN_RESULT -> LectureHpAttemptResult.UNKNOWN_RESULT;
        };
    }
}
