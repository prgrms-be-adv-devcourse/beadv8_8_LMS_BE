package com.hapbang.lecture.domain;

import static com.hapbang.lecture.domain.LectureHpAttemptResult.*;
import static com.hapbang.shared.billing.domain.BillingFailureCode.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.hapbang.shared.billing.domain.BillingFailureCode;
import com.hapbang.shared.billing.out.BillingCallFailureKind;

// 지갑 실패를 작업 유형별로 나눈다 (D57 (5)). 같은 코드라도 작업에 따라 뜻이 다르다
class LectureHpTaskClassifierTest {

    @Test
    void HOLD의_잔액_부족은_확정_거절이고_지갑_없음은_재시도다() {
        assertThat(LectureHpTaskClassifier.classify(LectureHpTaskType.HOLD, INSUFFICIENT_BALANCE))
                .isEqualTo(TERMINAL_REJECTION);
        assertThat(LectureHpTaskClassifier.classify(LectureHpTaskType.HOLD, WALLET_NOT_FOUND)).isEqualTo(RETRYABLE);
        assertThat(LectureHpTaskClassifier.classify(LectureHpTaskType.HOLD, HOLD_ALREADY_CLOSED))
                .isEqualTo(INVARIANT_VIOLATION);
    }

    @Test
    void RELEASE는_지갑_없음만_재시도고_나머지는_불변식_위반이다() {
        assertThat(LectureHpTaskClassifier.classify(LectureHpTaskType.RELEASE, WALLET_NOT_FOUND)).isEqualTo(RETRYABLE);
        assertThat(LectureHpTaskClassifier.classify(LectureHpTaskType.RELEASE, INSUFFICIENT_BALANCE))
                .isEqualTo(INVARIANT_VIOLATION);
        assertThat(LectureHpTaskClassifier.classify(LectureHpTaskType.RELEASE, HOLD_ALREADY_CLOSED))
                .isEqualTo(INVARIANT_VIOLATION);
    }

    @Test
    void PAYOUT의_이미_지급은_확정_거절이고_지갑_없음은_재시도다() {
        assertThat(LectureHpTaskClassifier.classify(LectureHpTaskType.PAYOUT, PAYOUT_ALREADY_SETTLED))
                .isEqualTo(TERMINAL_REJECTION);
        assertThat(LectureHpTaskClassifier.classify(LectureHpTaskType.PAYOUT, WALLET_NOT_FOUND)).isEqualTo(RETRYABLE);
        assertThat(LectureHpTaskClassifier.classify(LectureHpTaskType.PAYOUT, PAYOUT_TO_PAYER))
                .isEqualTo(INVARIANT_VIOLATION);
    }

    @Test
    void 처음_보는_코드는_어느_작업이든_불변식_위반이다() {
        for (LectureHpTaskType type : LectureHpTaskType.values()) {
            assertThat(LectureHpTaskClassifier.classify(type, BillingFailureCode.UNMAPPED))
                    .isEqualTo(INVARIANT_VIOLATION);
        }
    }

    @Test
    void 응답을_받지_못한_실패는_종류를_그대로_따른다() {
        assertThat(LectureHpTaskClassifier.classify(BillingCallFailureKind.RETRYABLE)).isEqualTo(RETRYABLE);
        assertThat(LectureHpTaskClassifier.classify(BillingCallFailureKind.UNKNOWN_RESULT)).isEqualTo(UNKNOWN_RESULT);
    }
}
