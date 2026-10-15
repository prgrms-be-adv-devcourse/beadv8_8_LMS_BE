package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.billing.BillingCallResult;
import com.hapbang.shared.billing.domain.BillingFailureCode;

// billing 결과를 강의의 HTTP 표현으로 바꾸는 규칙 (D62)
class LectureBillingFailureMapperTest {

    @ParameterizedTest
    @EnumSource(value = BillingFailureCode.class, mode = EnumSource.Mode.EXCLUDE, names = "UNMAPPED")
    void 공유_실패_코드마다_같은_이름의_강의_오류_코드로_바꾼다(BillingFailureCode code) {
        assertThat(LectureBillingFailureMapper.toLectureErrorCode(code).name()).isEqualTo(code.name());
    }

    @Test
    void UNMAPPED는_강의_HTTP_표현이_없어_IllegalStateException이다() {
        assertThatThrownBy(() -> LectureBillingFailureMapper.toLectureErrorCode(BillingFailureCode.UNMAPPED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 성공_결과는_값을_돌려준다() {
        assertThat(LectureBillingFailureMapper.valueOrThrow(new BillingCallResult.Succeeded<>("보류 결과")))
                .isEqualTo("보류 결과");
    }

    @Test
    void 실패_결과는_같은_코드와_billing_메시지의_강의_예외로_끝난다() {
        BillingCallResult<String> failed = new BillingCallResult.Failed<>(BillingFailureCode.INSUFFICIENT_BALANCE,
                "HP 잔액이 부족합니다.");

        assertThatThrownBy(() -> LectureBillingFailureMapper.valueOrThrow(failed))
                .isInstanceOfSatisfying(LectureException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(LectureErrorCode.INSUFFICIENT_BALANCE);
                    assertThat(exception.getMessage()).isEqualTo("HP 잔액이 부족합니다.");
                });
    }

    @Test
    void 실패_메시지가_없으면_강의_오류_코드의_기본_메시지를_쓴다() {
        BillingCallResult<String> failed = new BillingCallResult.Failed<>(BillingFailureCode.WALLET_NOT_FOUND, null);

        assertThatThrownBy(() -> LectureBillingFailureMapper.valueOrThrow(failed))
                .hasMessage(LectureErrorCode.WALLET_NOT_FOUND.getMessage());
    }
}
