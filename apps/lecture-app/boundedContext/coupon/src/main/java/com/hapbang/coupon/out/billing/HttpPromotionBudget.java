package com.hapbang.coupon.out.billing;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.hapbang.coupon.app.port.PromotionBudget;
import com.hapbang.coupon.domain.BudgetWorkKind;
import com.hapbang.coupon.out.billing.CouponBillingApiConfig.BudgetHttpResponse;
import com.hapbang.shared.billing.domain.PromotionBudgetWorkKind;
import com.hapbang.shared.billing.domain.PromotionBudgetWorkStatus;
import com.hapbang.shared.billing.dto.PromotionBudgetReleaseCommand;
import com.hapbang.shared.billing.dto.PromotionBudgetReleaseResultDto;
import com.hapbang.shared.billing.dto.PromotionBudgetReservationDto;
import com.hapbang.shared.billing.dto.PromotionBudgetRequests;
import com.hapbang.shared.billing.dto.PromotionBudgetReserveCommand;
import com.hapbang.shared.billing.dto.PromotionBudgetReserveResultDto;
import com.hapbang.shared.billing.dto.PromotionBudgetWorkDto;
import com.hapbang.shared.billing.out.BillingClientProperties;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * 쿠폰 예산 포트를 billing 내부 HTTP API에 연결한다. 작업의 재시도와 결과 저장은 호출자가 맡고 여기서는 한 번만 요청한다.
 * 업무 거절은 REJECTED, 응답 유실·일시 오류·해석할 수 없는 결과는 UNKNOWN으로 남긴다.
 * 회로는 기술 실패만 세며, 4xx와 작업 없음은 회로 실패로 세지 않는다.
 */
public class HttpPromotionBudget implements PromotionBudget {

    private static final Set<String> REJECTION_CODES = Set.of("PROMOTION_BALANCE_INSUFFICIENT",
            "BUDGET_RELEASE_EXCEEDS_REMAINING", "BUDGET_RESERVATION_NOT_FOUND", "IDEMPOTENCY_KEY_CONFLICT",
            "INVALID_POINT_AMOUNT");

    private final CouponBillingInternalApi api;
    private final CircuitBreaker circuitBreaker;

    public HttpPromotionBudget(CouponBillingInternalApi api, BillingClientProperties properties) {
        this.api = api;
        BillingClientProperties.CircuitBreaker settings = properties.resilience().circuitBreaker();
        this.circuitBreaker = CircuitBreaker.of("billing", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .slowCallDurationThreshold(settings.slowCallDuration())
                .slowCallRateThreshold(settings.slowCallRateThreshold())
                .waitDurationInOpenState(settings.openDuration())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(failure -> failure instanceof BudgetUnavailable)
                .build());
    }

    @Override
    public BudgetResult reserve(String workId, long couponId, int policyVersion, long amount) {
        PromotionBudgetReserveCommand command = new PromotionBudgetReserveCommand(workId, couponId, policyVersion,
                amount);
        return call(false, () -> {
            PromotionBudgetReserveResultDto result = data(api.reserve(command));
            if (!Objects.equals(result.workId(), workId) || result.couponId() != couponId || result.amountHp() != amount
                    || result.amountHp() <= 0 || !validReservationId(result.reservationId())) {
                throw new BudgetUnavailable("RESPONSE_MISMATCH");
            }
            return succeeded(result.reservationId());
        });
    }

    @Override
    public BudgetResult release(String workId, String reservationId, long amount, String target, String reason) {
        PromotionBudgetReleaseCommand command = new PromotionBudgetReleaseCommand(workId, reservationId, amount,
                target, reason);
        return call(false, () -> {
            PromotionBudgetReleaseResultDto result = data(api.release(command));
            if (!Objects.equals(result.workId(), workId) || !Objects.equals(result.reservationId(), reservationId)
                    || result.releasedHp() != amount || result.releasedHp() <= 0 || result.remainingHp() < 0) {
                throw new BudgetUnavailable("RESPONSE_MISMATCH");
            }
            return succeeded(result.reservationId());
        });
    }

    @Override
    public BudgetResult status(String workId) {
        PromotionBudgetRequests.requireWorkId(workId);
        return call(true, () -> {
            PromotionBudgetWorkDto result = data(api.status(workId));
            if (!Objects.equals(workId, result.workId())) {
                throw new BudgetUnavailable("RESPONSE_MISMATCH");
            }
            if (result.status() == PromotionBudgetWorkStatus.NOT_FOUND) {
                // 거절한 요청도 저장하지 않으므로 성공·거절로 확정하지 않고 같은 작업 ID를 다시 보낸다.
                return unknown("NOT_FOUND");
            }
            if (result.status() != PromotionBudgetWorkStatus.DONE || result.kind() == null
                    || !validReservationId(result.reservationId()) || result.amountHp() == null
                    || result.amountHp() <= 0) {
                throw new BudgetUnavailable("RESPONSE_MISMATCH");
            }
            return confirmedWork(result);
        });
    }

    private BudgetResult confirmedWork(PromotionBudgetWorkDto work) {
        PromotionBudgetReservationDto reservation;
        try {
            reservation = data(api.reservation(work.reservationId()));
        } catch (BudgetHttpResponse response) {
            if (response.status() >= 500) {
                throw new BudgetUnavailable("STATUS_METADATA_UNAVAILABLE");
            }
            return unknown("STATUS_METADATA_UNAVAILABLE");
        } catch (RestClientException unavailable) {
            throw new BudgetUnavailable(unavailable instanceof ResourceAccessException
                    ? "STATUS_METADATA_UNAVAILABLE" : "RESPONSE_MISMATCH", unavailable);
        }
        if (!Objects.equals(work.reservationId(), reservation.reservationId()) || reservation.couponId() <= 0
                || reservation.amountHp() <= 0
                || (work.kind() == PromotionBudgetWorkKind.RESERVE
                    && work.amountHp() != reservation.amountHp())) {
            throw new BudgetUnavailable("RESPONSE_MISMATCH");
        }
        return new BudgetResult(Outcome.SUCCEEDED, work.reservationId(), null,
                new WorkMetadata(work.workId(), BudgetWorkKind.valueOf(work.kind().name()), reservation.couponId(),
                        work.amountHp()));
    }

    private BudgetResult call(boolean lookup, Supplier<BudgetResult> request) {
        try {
            return circuitBreaker.executeSupplier(() -> {
                try {
                    return request.get();
                } catch (BudgetHttpResponse response) {
                    if (response.status() >= 500) {
                        throw new BudgetUnavailable("HTTP_" + response.status());
                    }
                    if (!lookup && response.status() >= 400 && response.status() < 500
                            && response.status() != 408 && response.status() != 425 && response.status() != 429
                            && response.code() != null && REJECTION_CODES.contains(response.code())) {
                        return new BudgetResult(Outcome.REJECTED, null, response.code());
                    }
                    // 예약 존재 충돌·설정/인증 오류·형식 불명은 새 작업 거절로 확정하지 않는다.
                    return unknown(response.code() == null ? "HTTP_" + response.status() : response.code());
                } catch (RestClientException unreadable) {
                    throw new BudgetUnavailable(lookup && !(unreadable instanceof ResourceAccessException)
                            ? "RESPONSE_MISMATCH" : "HTTP_UNAVAILABLE", unreadable);
                }
            });
        } catch (CallNotPermittedException open) {
            return unknown("CIRCUIT_OPEN");
        } catch (BudgetUnavailable unavailable) {
            return unknown(unavailable.getMessage());
        }
    }

    private static <T> T data(ResponseEntity<CouponBillingInternalApi.Envelope<T>> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null
                || response.getBody().data() == null) {
            throw new BudgetUnavailable("EMPTY_RESPONSE");
        }
        return response.getBody().data();
    }

    private static boolean validReservationId(String reservationId) {
        return reservationId != null && PromotionBudgetRequests.RESERVATION_ID.matcher(reservationId).matches();
    }

    private static BudgetResult succeeded(String reservationId) {
        return new BudgetResult(Outcome.SUCCEEDED, reservationId, null);
    }

    private static BudgetResult unknown(String reason) {
        return new BudgetResult(Outcome.UNKNOWN, null, reason);
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    private static final class BudgetUnavailable extends RuntimeException {
        BudgetUnavailable(String reason) {
            super(reason, null, false, false);
        }

        BudgetUnavailable(String reason, Throwable cause) {
            super(reason, cause, false, false);
        }
    }
}
