package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.hapbang.global.exception.BaseErrorCode;
import com.hapbang.global.exception.BusinessException;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.billing.BillingCallException;
import com.hapbang.lecture.out.billing.BillingCallFailureKind;
import com.hapbang.shared.billing.domain.BillingFailureCode;

// 지급 실패를 재시도로 풀릴 수 있는 일시 실패와 격리할 영구 실패로 나누는 규칙 (D54)
class PayoutFailureTest {

    @Test
    void 지갑_없음은_일시_실패다() {
        PayoutFailure failure = PayoutFailure.of(new LectureException(LectureErrorCode.WALLET_NOT_FOUND));

        assertThat(failure).isEqualTo(new PayoutFailure("WALLET_NOT_FOUND", false));
    }

    @ParameterizedTest
    @EnumSource(value = BillingFailureCode.class, mode = EnumSource.Mode.EXCLUDE, names = {"WALLET_NOT_FOUND", "UNMAPPED"})
    void 지갑_없음이_아닌_billing_실패는_영구_실패다(BillingFailureCode code) {
        PayoutFailure failure = PayoutFailure.of(
                new LectureException(LectureBillingFailureMapper.toLectureErrorCode(code), "billing 메시지"));

        assertThat(failure).isEqualTo(new PayoutFailure(code.name(), true));
    }

    @Test
    void 지급액_불일치는_영구_실패다() {
        PayoutFailure failure = PayoutFailure.of(new PayoutAmountMismatchException("지급액이 항목 가격과 다릅니다."));

        assertThat(failure).isEqualTo(new PayoutFailure("PAYOUT_AMOUNT_MISMATCH", true));
    }

    @Test
    void 업무_예외가_아니면_일시_실패이고_코드는_예외_클래스_이름이다() {
        assertThat(PayoutFailure.of(new ObjectOptimisticLockingFailureException(OrderItem.class, 1L)))
                .isEqualTo(new PayoutFailure("ObjectOptimisticLockingFailureException", false));
        assertThat(PayoutFailure.of(new CannotAcquireLockException("deadlock detected")))
                .isEqualTo(new PayoutFailure("CannotAcquireLockException", false));
        assertThat(PayoutFailure.of(new IllegalStateException("처리 실패")))
                .isEqualTo(new PayoutFailure("IllegalStateException", false));
    }

    @Test
    void 이름이_없는_예외_클래스는_전체_이름으로_남긴다() {
        RuntimeException anonymous = new RuntimeException() {
        };

        assertThat(PayoutFailure.of(anonymous).code()).isEqualTo(anonymous.getClass().getName());
    }

    @Test
    void 코드는_최대_100자로_자른다() {
        BaseErrorCode longCode = new BaseErrorCode() {
            @Override
            public HttpStatus getStatus() {
                return HttpStatus.CONFLICT;
            }

            @Override
            public String getMessage() {
                return "긴 코드";
            }

            @Override
            public String name() {
                return "X".repeat(150);
            }
        };

        assertThat(PayoutFailure.of(new BusinessException(longCode)).code()).hasSize(100);
    }

    @Test
    void billing_결과를_받지_못한_실패는_원인_예외_이름으로_일시_실패다() {
        BillingCallException exception = new BillingCallException(BillingCallFailureKind.RETRYABLE,
                new CannotAcquireLockException("deadlock detected"));

        assertThat(PayoutFailure.of(exception)).isEqualTo(new PayoutFailure("CannotAcquireLockException", false));
    }

    @Test
    void billing_결과를_받지_못한_실패의_진단_예외는_원인_예외다() {
        CannotAcquireLockException cause = new CannotAcquireLockException("deadlock detected");

        assertThat(PayoutFailure.diagnosticException(new BillingCallException(BillingCallFailureKind.RETRYABLE, cause)))
                .isSameAs(cause);
    }

    @Test
    void 그_밖의_예외는_그대로_진단_예외다() {
        PayoutAmountMismatchException mismatch = new PayoutAmountMismatchException("지급액이 항목 가격과 다릅니다.");

        assertThat(PayoutFailure.diagnosticException(mismatch)).isSameAs(mismatch);
    }
}
