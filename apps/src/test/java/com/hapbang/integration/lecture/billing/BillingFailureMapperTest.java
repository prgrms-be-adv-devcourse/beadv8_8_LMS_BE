package com.hapbang.integration.lecture.billing;

import static org.assertj.core.api.Assertions.*;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.out.billing.BillingCallResult;
import com.hapbang.shared.billing.domain.BillingFailureCode;

// billing 내부 오류 코드를 모듈 간 실패 코드로 바꾸는 규칙 (D62)
class BillingFailureMapperTest {

    /** 강의 billing 연동(보류·반환·지급)에서 나올 수 없는 billing 코드 */
    private static final Set<BillingErrorCode> IMPOSSIBLE = EnumSet.of(
            BillingErrorCode.ORIGINAL_LEDGER_NOT_FOUND,
            BillingErrorCode.INVALID_REVERSAL,
            BillingErrorCode.CAPTURE_AMOUNT_EXCEEDS_HELD,
            BillingErrorCode.CHARGE_UNAVAILABLE,
            BillingErrorCode.CHARGE_AMOUNT_INVALID,
            BillingErrorCode.CHARGE_NOT_FOUND,
            BillingErrorCode.CHARGE_AMOUNT_MISMATCH,
            BillingErrorCode.CHARGE_PAYMENT_KEY_CONFLICT,
            BillingErrorCode.CHARGE_ALREADY_FAILED,
            BillingErrorCode.CHARGE_PAYMENT_REJECTED,
            BillingErrorCode.CHARGE_STATE_INCONSISTENT);

    @ParameterizedTest
    @EnumSource(value = BillingErrorCode.class, mode = EnumSource.Mode.EXCLUDE, names = {"ORIGINAL_LEDGER_NOT_FOUND", "INVALID_REVERSAL",
            "CAPTURE_AMOUNT_EXCEEDS_HELD", "CHARGE_UNAVAILABLE", "CHARGE_AMOUNT_INVALID", "CHARGE_NOT_FOUND",
            "CHARGE_AMOUNT_MISMATCH", "CHARGE_PAYMENT_KEY_CONFLICT", "CHARGE_ALREADY_FAILED", "CHARGE_PAYMENT_REJECTED",
            "CHARGE_STATE_INCONSISTENT"})
    void 강의_연동에서_나올_수_있는_billing_코드는_같은_이름의_실패와_billing_메시지로_바꾼다(BillingErrorCode code) {
        BillingCallResult.Failed<Object> failed = BillingFailureMapper.toFailed(new BillingException(code));

        assertThat(failed.code().name()).isEqualTo(code.name());
        assertThat(failed.detail()).isEqualTo(code.getMessage());
    }

    @ParameterizedTest
    @EnumSource(value = BillingErrorCode.class, names = {"ORIGINAL_LEDGER_NOT_FOUND", "INVALID_REVERSAL",
            "CAPTURE_AMOUNT_EXCEEDS_HELD", "CHARGE_UNAVAILABLE", "CHARGE_AMOUNT_INVALID", "CHARGE_NOT_FOUND",
            "CHARGE_AMOUNT_MISMATCH", "CHARGE_PAYMENT_KEY_CONFLICT", "CHARGE_ALREADY_FAILED", "CHARGE_PAYMENT_REJECTED",
            "CHARGE_STATE_INCONSISTENT"})
    void 강의_연동에서_나올_수_없는_billing_코드는_원래_예외를_담은_IllegalStateException이다(BillingErrorCode code) {
        BillingException exception = new BillingException(code);

        assertThatThrownBy(() -> BillingFailureMapper.toFailed(exception))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(exception);
    }

    @Test
    void 상세_메시지가_있으면_그_메시지를_돌려준다() {
        BillingException exception = new BillingException(BillingErrorCode.HOLD_ALREADY_CLOSED, "이미 반환된 보류입니다.");

        assertThat(BillingFailureMapper.toFailed(exception).detail()).isEqualTo("이미 반환된 보류입니다.");
    }

    @Test
    void 나올_수_있는_코드는_공유_실패_코드_전체와_같다() {
        Set<String> possible = EnumSet.complementOf(EnumSet.copyOf(IMPOSSIBLE)).stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(possible).containsExactlyInAnyOrderElementsOf(EnumSet.complementOf(
                EnumSet.of(BillingFailureCode.UNMAPPED)).stream().map(Enum::name).toList());
    }

    @ParameterizedTest
    @EnumSource(value = BillingFailureCode.class, mode = EnumSource.Mode.EXCLUDE, names = "UNMAPPED")
    void 강의_오류_코드의_HTTP_상태와_기본_메시지는_billing_오류_코드와_같다(BillingFailureCode code) {
        LectureErrorCode lecture = LectureErrorCode.valueOf(code.name());
        BillingErrorCode billing = BillingErrorCode.valueOf(code.name());

        assertThat(lecture.getStatus()).isEqualTo(billing.getStatus());
        assertThat(lecture.getMessage()).isEqualTo(billing.getMessage());
    }
}
