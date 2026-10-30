package com.hapbang.billing.in;

import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.billing.app.HoldFacade;
import com.hapbang.billing.global.exception.BusinessException;
import com.hapbang.billing.global.response.RsData;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.dto.PayoutResultDto;
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.PenaltyResultDto;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.dto.ReleaseResultDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지갑 내부 API (#273). 계약은 docs/api/internal-billing.md, 정책은 docs/billing/CONTEXT.md 1장, Swagger 명세는 {@link ApiV1BillingInternalSpec}.
 * <ul>
 *     <li>요청 본문은 shared Command를 그대로 받는다. 구조 검사(필수 값·참조 유형) 실패는 400 {@code INVALID_REQUEST}다.</li>
 *     <li>요청 하나를 Billing 로컬 트랜잭션 하나로 처리한다(UseCase가 연다). 인증은 {@code InternalApiSecurityConfig}가,
 *     인가는 {@code HoldFacade}의 {@code hasRole('INTERNAL')}이 한다.</li>
 *     <li>요청마다 요청 ID·참조 ID와 결과(재요청 여부 또는 오류 code)를 key=value 한 줄로 남긴다. correlation ID는 계약상 받지 않는다.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/billing")
@RequiredArgsConstructor
public class ApiV1BillingInternalController implements ApiV1BillingInternalSpec {

    private final HoldFacade holdFacade;

    @Override
    @PostMapping("/holds")
    public RsData<HoldResultDto> hold(@RequestBody HoldCommand command) {
        String request = "operation=HOLD userId=%d referenceType=%s referenceId=%d amountHp=%d".formatted(
                command.userId(), command.referenceType(), command.referenceId(), command.amountHp());
        HoldResultDto result = logged(request, () -> BillingResultDtos.hold(holdFacade.hold(command)),
                HoldResultDto::replayed);
        return RsData.ok("HP를 보류했습니다.", result);
    }

    @Override
    @PostMapping("/releases")
    public RsData<ReleaseResultDto> release(@RequestBody ReleaseCommand command) {
        String request = "operation=RELEASE userId=%d referenceType=%s referenceId=%d releaseRequestId=%s amountHp=%s"
                .formatted(command.userId(), command.referenceType(), command.referenceId(),
                        command.releaseRequestId(), command.amountHp());
        ReleaseResultDto result = logged(request,
                () -> BillingResultDtos.release(command, holdFacade.release(command)), ReleaseResultDto::replayed);
        return RsData.ok("HP를 반환했습니다.", result);
    }

    @Override
    @PostMapping("/payouts")
    public RsData<PayoutResultDto> payout(@RequestBody PayoutCommand command) {
        String request = ("operation=PAYOUT payerUserId=%d referenceType=%s referenceId=%d payoutRequestId=%s "
                + "receiverUserId=%d sourceType=%s sourceId=%d amountHp=%s").formatted(command.payerUserId(),
                command.referenceType(), command.referenceId(), command.payoutRequestId(), command.receiverUserId(),
                command.sourceType(), command.sourceId(), command.amountHp());
        PayoutResultDto result = logged(request,
                () -> BillingResultDtos.payout(command, holdFacade.payout(command)), PayoutResultDto::replayed);
        return RsData.ok("HP를 지급했습니다.", result);
    }

    @Override
    @PostMapping("/penalty-settlements")
    public RsData<PenaltyResultDto> settlePenalty(@RequestBody PenaltyCommand command) {
        String request = ("operation=PENALTY payerUserId=%d referenceType=%s referenceId=%d cancellationId=%s "
                + "receiverUserId=%d sourceType=%s sourceId=%d releaseAmountHp=%d payoutAmountHp=%d").formatted(
                command.payerUserId(), command.referenceType(), command.referenceId(), command.cancellationId(),
                command.receiverUserId(), command.sourceType(), command.sourceId(), command.releaseAmountHp(),
                command.payoutAmountHp());
        PenaltyResultDto result = logged(request,
                () -> BillingResultDtos.penalty(command, holdFacade.settlePenalty(command)), PenaltyResultDto::replayed);
        return RsData.ok("위약금을 정산했습니다.", result);
    }

    @Override
    @PostMapping("/hold-voids")
    public RsData<HoldVoidResultDto> voidHold(@RequestBody HoldVoidCommand command) {
        String request = "operation=HOLD_VOID userId=%d referenceType=%s referenceId=%d".formatted(
                command.userId(), command.referenceType(), command.referenceId());
        HoldVoidResultDto result = logged(request, () -> BillingResultDtos.holdVoid(holdFacade.voidHold(command)),
                HoldVoidResultDto::replayed);
        return RsData.ok("HP 보류를 무효화했습니다.", result);
    }

    // 처리 결과를 요청 값과 함께 한 줄로 남긴다. 업무 오류는 code만 남기고 그대로 던진다(응답·상세 로그는 GlobalExceptionHandler).
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
