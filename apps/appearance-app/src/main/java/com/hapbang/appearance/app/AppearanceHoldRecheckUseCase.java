package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.AppearanceHoldRejectedException;
import com.hapbang.appearance.out.billing.BillingClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HP 보류 재확인 (배치). 보류 결과를 확인하지 못해 {@code HOLD_PENDING}으로 남은 요청서·모집글에 같은 보류를 다시 요청한다.
 * <ul>
 *     <li>Billing은 같은 참조 대상의 보류를 다시 받으면 처음 결과를 돌려준다. 그래서 처음 요청이 처리됐어도 두 번 보류되지 않는다.</li>
 *     <li>결과: 성공 → {@code REQUESTED}/{@code RECRUITING}, 지갑 거절 → {@code HOLD_FAILED},
 *     여전히 알 수 없음 → 그대로 두고 다음 주기에 다시 확인</li>
 *     <li>만든 지 대기 시간({@code pendingGrace})이 지난 것만 가져간다. 진행 중인 보내기·올리기 요청과 겹치지 않도록 하기 위해서다.
 *     겹쳐도 Billing이 같은 결과를 주고 상태 반영이 멱등이라 안전하다.</li>
 *     <li>수락 기한이 이미 지난 요청서도 보류 결과대로 정리한다. 만료는 만료 배치가 이어서 처리한다 (ISSUE.md APR-26 S5).</li>
 *     <li><b>포기 (#536)</b>: 만든 지 포기 기준({@code holdVoidAfter}, 기본 1시간)이 지나도 결과를 모르는 건에는 보류 대신 보류 무효화를 보내
 *     {@code HOLD_FAILED}로 끝낸다(수락 기한이 지난 요청서도 같다). 무효화 결과를 모르면 다음 회차에 다시 무효화하고,
 *     지갑이 무효화를 거절하면 경고만 남기고 그대로 둔다. 포기 기준 전에는 지금처럼 보류를 다시 보낸다.</li>
 *     <li>보류 요청과 결과 반영은 보내기·올리기의 HoldUseCase를 그대로 쓴다.</li>
 *     <li>지갑 회로가 열려 있으면 그 회차를 건너뛰고, 회차 도중에 열리면 남은 건을 다음 회차로 넘긴다 (#439, {@link BillingCircuitGate}).
 *     재확인은 시도 횟수 제한이 없어 격리될 일은 없지만, 열린 회로에는 요청이 나가지 않아 결과가 바뀌지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceHoldRecheckUseCase {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceRequestHoldUseCase appearanceRequestHoldUseCase;
    private final AppearanceRecruitHoldUseCase appearanceRecruitHoldUseCase;
    private final AppearanceBatchProperties batchProperties;
    private final BillingClient billingClient;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;
    private final BillingCircuitGate circuitGate = new BillingCircuitGate("HP 보류 재확인");

    /**
     * @return 결과를 정리한 요청서·모집글 수 (성공·거절)
     */
    public int recheckDue() {
        int resolved = circuitGate.allows(billingClient) ? recheckStale() : 0;
        if (resolved > 0) {
            log.info("HP 보류 재확인 resolved={}", resolved);
        }
        return resolved;
    }

    private int recheckStale() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minus(batchProperties.pendingGrace());
        LocalDateTime voidCutoff = now.minus(batchProperties.holdVoidAfter());
        int resolved = 0;
        for (Long requestId : appearanceRequestSupport.findStaleHoldPendingIds(cutoff, batchProperties.batchSize())) {
            // 시험 중에 보낸 보류가 실패하면 회로가 다시 열린다. 남은 건은 다음 회차로 넘긴다
            if (!circuitGate.allows(billingClient)) {
                return resolved;
            }
            if (recheck("요청서", requestId, () -> recheckRequest(requestId, voidCutoff))) {
                resolved++;
            }
        }
        List<Long> recruitIds = appearanceRecruitSupport.findStaleHoldPendingIds(cutoff, batchProperties.batchSize());
        for (Long recruitId : recruitIds) {
            if (!circuitGate.allows(billingClient)) {
                return resolved;
            }
            if (recheck("모집글", recruitId, () -> recheckRecruit(recruitId, voidCutoff))) {
                resolved++;
            }
        }
        return resolved;
    }

    /** @return 결과를 정리했으면 true. 무효화를 지갑이 거절해 그대로 둔 경우 false */
    private boolean recheckRequest(Long requestId, LocalDateTime voidCutoff) {
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        if (request.getStatus() != AppearanceRequestStatus.HOLD_PENDING) {
            // 조회한 뒤 보내기 요청이 먼저 정리했다
            return true;
        }
        if (isVoidDue(request.getCreatedAt(), voidCutoff)) {
            log.info("HP 보류 포기: 포기 기준이 지나 보류 무효화를 보낸다 요청서 id={}, createdAt={}", requestId,
                    request.getCreatedAt());
            return appearanceRequestHoldUseCase.voidHold(request);
        }
        appearanceRequestHoldUseCase.requestHold(request);
        appearanceRequestHoldUseCase.markRequested(requestId);
        return true;
    }

    /** @return 결과를 정리했으면 true. 무효화를 지갑이 거절해 그대로 둔 경우 false */
    private boolean recheckRecruit(Long recruitId, LocalDateTime voidCutoff) {
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(recruitId);
        if (recruit.getStatus() != AppearanceRecruitStatus.HOLD_PENDING) {
            return true;
        }
        if (isVoidDue(recruit.getCreatedAt(), voidCutoff)) {
            log.info("HP 보류 포기: 포기 기준이 지나 보류 무효화를 보낸다 모집글 id={}, createdAt={}", recruitId,
                    recruit.getCreatedAt());
            return appearanceRecruitHoldUseCase.voidHold(recruit);
        }
        appearanceRecruitHoldUseCase.requestHold(recruit);
        appearanceRecruitHoldUseCase.markRecruiting(recruitId);
        return true;
    }

    /** 만든 지 포기 기준이 지났는지 (만든 시각 ≤ 지금 − 포기 기준) */
    private static boolean isVoidDue(LocalDateTime createdAt, LocalDateTime voidCutoff) {
        return !createdAt.isAfter(voidCutoff);
    }

    /**
     * 한 건을 재확인한다. 예외는 여기서 잡아 다음 건을 계속 처리한다.
     *
     * @return 결과를 정리했으면(성공·지갑 거절·무효화) true, 여전히 알 수 없거나 무효화가 거절되거나 예외면 false
     */
    private boolean recheck(String target, Long id, BooleanSupplier action) {
        try {
            return action.getAsBoolean();
        } catch (AppearanceHoldRejectedException rejected) {
            // 지갑 거절: HoldUseCase가 HOLD_FAILED로 기록했다
            return true;
        } catch (AppearanceException exception) {
            if (exception.getErrorCode() == AppearanceErrorCode.BILLING_UNAVAILABLE) {
                // 여전히 결과를 알 수 없음: 그대로 두고 다음 주기에 다시 확인한다
                return false;
            }
            log.warn("HP 보류 재확인 중 예외 {} id={}", target, id, exception);
            return false;
        } catch (RuntimeException exception) {
            log.warn("HP 보류 재확인 중 예외 {} id={}", target, id, exception);
            return false;
        }
    }
}
