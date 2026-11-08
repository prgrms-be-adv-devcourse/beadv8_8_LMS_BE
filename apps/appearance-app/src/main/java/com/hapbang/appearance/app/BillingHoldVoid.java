package com.hapbang.appearance.app;

import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.extern.slf4j.Slf4j;

/**
 * 보류 무효화(#536) 결과를 로그로 남긴다. 요청서·모집글 HoldUseCase가 같이 쓴다.
 * <ul>
 *     <li>{@code RELEASED}: 지갑에 실제로 묶였던 HP가 있었다는 뜻이라 반환액과 함께 경고로 남긴다.</li>
 *     <li>{@code ALREADY_CLOSED}: 출연은 {@code HOLD_PENDING} 동안 반환·지급을 보내지 않으므로 "이미 끝난 보류"는 예상 밖 상태다. 경고로 남긴다.</li>
 *     <li>{@code VOIDED}: 보류가 없어 무효 표시만 남겼다는 뜻이라 info로 남긴다.</li>
 *     <li>지갑 거절({@code Failed}, 예: {@code HOLD_OWNER_MISMATCH}): 계약과 다른 상태라 운영 확인 대상이다. 경고로 남기고 그대로 둔다.</li>
 * </ul>
 */
@Slf4j
final class BillingHoldVoid {

    private BillingHoldVoid() {
    }

    static void logVoided(String target, Long id, HoldVoidResultDto result) {
        if (result.outcome() == HoldVoidOutcome.RELEASED) {
            log.warn("HP 보류 무효화: 지갑에 묶였던 보류를 반환하고 {}을 HOLD_FAILED로 둔다 id={}, releasedHp={}, replayed={}",
                    target, id, result.releasedHp(), result.replayed());
            return;
        }
        if (result.outcome() == HoldVoidOutcome.ALREADY_CLOSED) {
            // 출연은 HOLD_PENDING 동안 반환·지급을 보내지 않으므로 "이미 끝난 보류"는 예상 밖 상태다 (강의 #535도 경고)
            log.warn("HP 보류 무효화: 보류가 이미 끝나 있었다(예상 밖), {}을 HOLD_FAILED로 둔다 id={}, replayed={}", target, id,
                    result.replayed());
            return;
        }
        log.info("HP 보류 무효화: {}을 HOLD_FAILED로 둔다 id={}, outcome={}, replayed={}", target, id, result.outcome(),
                result.replayed());
    }

    static void logRejected(String target, Long id, BillingCallResult.Failed<?> rejected) {
        log.warn("HP 보류 무효화 거절, {}을 HOLD_PENDING으로 둔다(운영 확인 필요) id={}, code={}, rawCode={}, httpStatus={}, detail={}",
                target, id, rejected.code(), rejected.rawCode(), rejected.httpStatus(), rejected.detail());
    }
}
