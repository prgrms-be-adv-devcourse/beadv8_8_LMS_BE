package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

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
 *     <li>보류 요청과 결과 반영은 보내기·올리기의 HoldUseCase를 그대로 쓴다.</li>
 *     <li>지갑 회로가 열려 있으면 그 회차를 건너뛴다 (#439). 재확인은 시도 횟수 제한이 없어 격리될 일은 없지만,
 *     열린 회로에는 요청이 나가지 않아 결과가 바뀌지 않는다.</li>
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

    /**
     * @return 결과를 정리한 요청서·모집글 수 (성공·거절)
     */
    public int recheckDue() {
        if (billingClient.isCircuitOpen()) {
            log.info("지갑 회로가 열려 HP 보류 재확인 회차를 건너뛴다");
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(batchProperties.pendingGrace());
        int resolved = 0;
        for (Long requestId : appearanceRequestSupport.findStaleHoldPendingIds(cutoff, batchProperties.batchSize())) {
            if (recheck("요청서", requestId, () -> recheckRequest(requestId))) {
                resolved++;
            }
        }
        List<Long> recruitIds = appearanceRecruitSupport.findStaleHoldPendingIds(cutoff, batchProperties.batchSize());
        for (Long recruitId : recruitIds) {
            if (recheck("모집글", recruitId, () -> recheckRecruit(recruitId))) {
                resolved++;
            }
        }
        if (resolved > 0) {
            log.info("HP 보류 재확인 resolved={}", resolved);
        }
        return resolved;
    }

    private void recheckRequest(Long requestId) {
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        if (request.getStatus() != AppearanceRequestStatus.HOLD_PENDING) {
            // 조회한 뒤 보내기 요청이 먼저 정리했다
            return;
        }
        appearanceRequestHoldUseCase.requestHold(request);
        appearanceRequestHoldUseCase.markRequested(requestId);
    }

    private void recheckRecruit(Long recruitId) {
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(recruitId);
        if (recruit.getStatus() != AppearanceRecruitStatus.HOLD_PENDING) {
            return;
        }
        appearanceRecruitHoldUseCase.requestHold(recruit);
        appearanceRecruitHoldUseCase.markRecruiting(recruitId);
    }

    /**
     * 한 건을 재확인한다. 예외는 여기서 잡아 다음 건을 계속 처리한다.
     *
     * @return 결과를 정리했으면(성공 또는 지갑 거절) true, 여전히 알 수 없거나 예외면 false
     */
    private boolean recheck(String target, Long id, Runnable action) {
        try {
            action.run();
            return true;
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
