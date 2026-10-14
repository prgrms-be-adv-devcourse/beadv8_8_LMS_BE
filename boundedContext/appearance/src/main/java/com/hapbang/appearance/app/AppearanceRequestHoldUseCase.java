package com.hapbang.appearance.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.out.billing.BillingClient.HoldCommand;
import com.hapbang.appearance.out.billing.BillingClient.HoldResult;
import com.hapbang.appearance.out.billing.BillingClient.RefType;
import com.hapbang.appearance.out.billing.BillingUnavailableException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연 요청서의 HP 보류 (보내기의 두 번째·세 번째 단계).
 * <ul>
 *     <li>{@link #requestHold}: Billing에 보류를 요청한다. DB 트랜잭션 밖에서 부른다 (Billing을 기다리는 동안 DB 연결을 잡지 않도록).</li>
 *     <li>{@link #markRequested}, {@link #markHoldFailed}: 보류 결과를 요청서에 반영한다. 각자 트랜잭션을 연다.</li>
 * </ul>
 * 이후 {@code HOLD_PENDING} 재확인 스케줄러도 같은 멱등 키로 이 기능을 다시 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceRequestHoldUseCase {

    private static final String HOLD_ACTION = "HOLD";
    private static final int FIRST = 1;

    private final BillingClient billingClient;
    private final AppearanceRequestSupport appearanceRequestSupport;

    /**
     * 출연료만큼 호스트의 HP 보류를 요청한다. 멱등 키: {@code APPEARANCE_REQUEST-{요청서 ID}-HOLD-1}
     *
     * @throws AppearanceException Billing이 응답하지 않을 때({@code BILLING_UNAVAILABLE}). 요청서는 {@code HOLD_PENDING}으로 남는다
     */
    public HoldResult requestHold(AppearanceRequest request) {
        String idempotencyKey = BillingClient.idempotencyKey(RefType.APPEARANCE_REQUEST, request.getId(),
                HOLD_ACTION, FIRST);
        HoldCommand command = new HoldCommand(idempotencyKey, RefType.APPEARANCE_REQUEST, request.getId(),
                request.getHostId(), request.getFee());
        try {
            return billingClient.hold(command);
        } catch (BillingUnavailableException exception) {
            log.warn("HP 보류 무응답, 요청서를 HOLD_PENDING으로 둔다 requestId={}", request.getId(), exception);
            throw new AppearanceException(AppearanceErrorCode.BILLING_UNAVAILABLE);
        }
    }

    /**
     * 보류 성공 → {@code REQUESTED}.
     * 응답에 담는 버전이 DB와 같도록 바로 반영(flush)한 뒤 응답을 만든다. 게스트는 이 버전으로 수락한다.
     */
    @Transactional
    public AppearanceRequestResDto markRequested(Long requestId) {
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        request.markRequested();
        appearanceRequestSupport.flush();
        return AppearanceRequestResDto.from(request);
    }

    /** 잔액 부족 → {@code HOLD_FAILED} (기록으로만 남긴다) */
    @Transactional
    public void markHoldFailed(Long requestId) {
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        request.markHoldFailed();
    }
}
