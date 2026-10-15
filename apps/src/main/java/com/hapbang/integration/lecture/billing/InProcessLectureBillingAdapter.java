package com.hapbang.integration.lecture.billing;

import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.hapbang.billing.app.HoldFacade;
import com.hapbang.billing.app.HoldResult;
import com.hapbang.billing.app.PayoutResult;
import com.hapbang.billing.app.ReleaseResult;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.lecture.out.billing.BillingCallException;
import com.hapbang.lecture.out.billing.BillingCallFailureKind;
import com.hapbang.lecture.out.billing.BillingCallResult;
import com.hapbang.lecture.out.billing.BillingClient;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

import lombok.RequiredArgsConstructor;

// 강의가 소유한 BillingClient를 같은 JVM의 billing 진입점(HoldFacade)에 잇는다 (설계 6.1, D56·D62).
// billing 내부 결과·예외는 이 클래스 밖으로 내보내지 않는다: 성공은 shared DTO, billing 업무 예외는 Failed, 그 밖의 예외는 BillingCallException이다.
// 지금은 강의 트랜잭션 안에서 불려 billing이 그 트랜잭션에 참여한다. 그래서 기술 예외가 나면 강의·billing 변경이 함께 롤백되므로 RETRYABLE이다.
// 트랜잭션 밖 호출 강제(@Transactional(NEVER))는 흐름을 옮기는 티켓(#249·#250·#251)에서 붙인다.
@Component
@RequiredArgsConstructor
public class InProcessLectureBillingAdapter implements BillingClient {

    private final HoldFacade holdFacade;

    @Override
    public BillingCallResult<HoldResultDto> hold(HoldCommand command) {
        return call(() -> holdFacade.hold(command), this::toDto);
    }

    @Override
    public BillingCallResult<ReleaseResultDto> release(ReleaseCommand command) {
        return call(() -> holdFacade.release(command), result -> toDto(command, result));
    }

    @Override
    public BillingCallResult<PayoutResultDto> payout(PayoutCommand command) {
        return call(() -> holdFacade.payout(command), result -> toDto(command, result));
    }

    // billing 호출과 결과 변환을 나눈다. 변환 중에 생긴 예외(나올 수 없는 billing 코드 등)를 기술 실패로 다시 감싸지 않기 위해서다.
    private <R, T> BillingCallResult<T> call(Supplier<R> billingCall, Function<R, T> toDto) {
        Outcome<R> outcome = invoke(billingCall);
        if (outcome.rejection() != null) {
            return BillingFailureMapper.toFailed(outcome.rejection());
        }
        return new BillingCallResult.Succeeded<>(toDto.apply(outcome.result()));
    }

    private static <R> Outcome<R> invoke(Supplier<R> billingCall) {
        try {
            return new Outcome<>(billingCall.get(), null);
        } catch (BillingException rejection) {
            return new Outcome<>(null, rejection);
        } catch (RuntimeException failure) {
            throw new BillingCallException(BillingCallFailureKind.RETRYABLE, failure);
        }
    }

    private HoldResultDto toDto(HoldResult result) {
        return new HoldResultDto(result.referenceType(), result.referenceId(), result.totalHp(), result.replayed());
    }

    private ReleaseResultDto toDto(ReleaseCommand command, ReleaseResult result) {
        return new ReleaseResultDto(result.referenceType(), result.referenceId(), command.releaseRequestId(),
                result.releasedHp(), result.remainingHeldHp(), result.replayed());
    }

    // 받는 회원은 요청 값이다. 재요청에서 받는 지갑이 처음과 다르면 billing이 IDEMPOTENCY_KEY_CONFLICT로 거절하므로 결과와 같다.
    private PayoutResultDto toDto(PayoutCommand command, PayoutResult result) {
        return new PayoutResultDto(result.referenceType(), result.referenceId(), command.payoutRequestId(),
                command.receiverUserId(), result.sourceType(), result.sourceId(), result.paidHp(),
                result.remainingHeldHp(), result.replayed());
    }

    /** billing 호출 결과: 결과 또는 업무 거절 중 하나 */
    private record Outcome<R>(R result, BillingException rejection) {
    }
}
