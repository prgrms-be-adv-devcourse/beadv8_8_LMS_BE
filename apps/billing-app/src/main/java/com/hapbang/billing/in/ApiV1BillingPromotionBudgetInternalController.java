package com.hapbang.billing.in;

import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.BudgetReleaseCommand;
import com.hapbang.billing.app.BudgetReleaseResult;
import com.hapbang.billing.app.BudgetReservationResult;
import com.hapbang.billing.app.BudgetReserveCommand;
import com.hapbang.billing.app.BudgetReserveResult;
import com.hapbang.billing.app.BudgetWorkResult;
import com.hapbang.billing.app.PromotionBudgetFacade;
import com.hapbang.billing.global.exception.BusinessException;
import com.hapbang.billing.global.exception.GlobalErrorCode;
import com.hapbang.billing.global.response.RsData;
import com.hapbang.shared.billing.dto.PromotionBudgetReleaseCommand;
import com.hapbang.shared.billing.dto.PromotionBudgetReleaseResultDto;
import com.hapbang.shared.billing.dto.PromotionBudgetRequests;
import com.hapbang.shared.billing.dto.PromotionBudgetReservationDto;
import com.hapbang.shared.billing.dto.PromotionBudgetReserveCommand;
import com.hapbang.shared.billing.dto.PromotionBudgetReserveResultDto;
import com.hapbang.shared.billing.dto.PromotionBudgetWorkDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 예산 내부 API. 계약은 docs/api/internal-billing.md, Swagger 명세는 {@link ApiV1BillingPromotionBudgetInternalSpec}.
 * <ul>
 *     <li>요청 본문은 shared Command를 그대로 받는다. 구조 검사(작업 ID·예약 ID 형식 등) 실패는 400 {@code INVALID_REQUEST}다.</li>
 *     <li>인증은 {@code InternalApiSecurityConfig}가, 인가는 {@code PromotionBudgetFacade}의 {@code hasRole('INTERNAL')}이 한다.</li>
 *     <li>예약 ID는 응답에서 "PB-{예약 행 ID}"로 주고, 요청에서 같은 형식을 받아 예약 행 ID로 바꾼다.</li>
 *     <li>예약·해제는 요청마다 작업 ID·쿠폰·예약·금액과 결과(재요청 여부 또는 오류 code)를 한 줄로 남긴다. 해제 사유는 남기지 않는다.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/billing/promotion-budgets")
@RequiredArgsConstructor
public class ApiV1BillingPromotionBudgetInternalController implements ApiV1BillingPromotionBudgetInternalSpec {

    private final PromotionBudgetFacade promotionBudgetFacade;

    @Override
    @PostMapping("/reservations")
    public RsData<PromotionBudgetReserveResultDto> reserve(@RequestBody PromotionBudgetReserveCommand command) {
        String request = "operation=BUDGET_RESERVE workId=%s couponId=%d policyVersion=%d amountHp=%d".formatted(
                command.workId(), command.couponId(), command.policyVersion(), command.amountHp());
        BudgetReserveResult result = logged(request, () -> promotionBudgetFacade.reserve(new BudgetReserveCommand(
                command.workId(), command.couponId(), command.policyVersion(), command.amountHp())),
                BudgetReserveResult::replayed);
        return RsData.ok("쿠폰 예산을 예약했습니다.", new PromotionBudgetReserveResultDto(result.workId(),
                PromotionBudgetRequests.reservationId(result.reservationId()), result.couponId(), result.amountHp(),
                result.replayed()));
    }

    @Override
    @PostMapping("/releases")
    public RsData<PromotionBudgetReleaseResultDto> release(@RequestBody PromotionBudgetReleaseCommand command) {
        String request = "operation=BUDGET_RELEASE workId=%s reservationId=%s amountHp=%d".formatted(
                command.workId(), command.reservationId(), command.amountHp());
        BudgetReleaseResult result = logged(request, () -> promotionBudgetFacade.release(new BudgetReleaseCommand(
                command.workId(), PromotionBudgetRequests.reservationRowId(command.reservationId()),
                command.amountHp(), command.target(), command.reason())), BudgetReleaseResult::replayed);
        return RsData.ok("쿠폰 예산을 해제했습니다.", new PromotionBudgetReleaseResultDto(result.workId(),
                PromotionBudgetRequests.reservationId(result.reservationId()), result.releasedHp(), result.remainingHp(),
                result.replayed()));
    }

    // 조회라 HP를 움직이지 않아 요청 로그를 남기지 않는다
    @Override
    @GetMapping("/works/{workId}")
    public RsData<PromotionBudgetWorkDto> getWork(@PathVariable("workId") String workId) {
        try {
            PromotionBudgetRequests.requireWorkId(workId);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(GlobalErrorCode.INVALID_REQUEST, exception.getMessage());
        }
        BudgetWorkResult result = promotionBudgetFacade.work(workId);
        return RsData.ok("쿠폰 예산 작업을 조회했습니다.", new PromotionBudgetWorkDto(result.workId(), result.kind(),
                result.status(), result.reservationId() == null ? null
                        : PromotionBudgetRequests.reservationId(result.reservationId()), result.amountHp()));
    }

    @Override
    @GetMapping("/reservations/{reservationId}")
    public RsData<PromotionBudgetReservationDto> getReservation(@PathVariable("reservationId") String reservationId) {
        long rowId;
        try {
            rowId = PromotionBudgetRequests.reservationRowId(reservationId);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(GlobalErrorCode.INVALID_REQUEST, exception.getMessage());
        }
        BudgetReservationResult result = promotionBudgetFacade.reservation(rowId);
        return RsData.ok("쿠폰 예산 예약을 조회했습니다.", new PromotionBudgetReservationDto(
                PromotionBudgetRequests.reservationId(result.reservationId()), result.couponId(), result.amountHp(),
                result.remainingHp(), result.heldHp(), result.paidHp(), result.releasedHp()));
    }

    // 처리 결과를 요청 값과 함께 한 줄로 남긴다(ApiV1BillingInternalController와 같은 형식).
    // 업무 오류는 code만 남기고 그대로 던진다(응답·상세 로그는 GlobalExceptionHandler).
    private <T> T logged(String request, Supplier<T> action, Predicate<T> replayed) {
        try {
            T result = action.get();
            log.info("내부 HP 요청 {} result=OK replayed={}", request, replayed.test(result));
            return result;
        } catch (BusinessException exception) {
            log.info("내부 HP 요청 {} result=FAILED code={}", request, exception.getErrorCode().getCode());
            throw exception;
        } catch (RuntimeException exception) {
            log.info("내부 HP 요청 {} result=ERROR exception={}", request, exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
