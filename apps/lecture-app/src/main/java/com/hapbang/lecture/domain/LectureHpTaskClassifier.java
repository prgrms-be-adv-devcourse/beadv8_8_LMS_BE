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
            case HOLD -> code == BillingFailureCode.INSUFFICIENT_BALANCE
                    ? LectureHpAttemptResult.TERMINAL_REJECTION : LectureHpAttemptResult.INVARIANT_VIOLATION;
            case RELEASE -> LectureHpAttemptResult.INVARIANT_VIOLATION;
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
