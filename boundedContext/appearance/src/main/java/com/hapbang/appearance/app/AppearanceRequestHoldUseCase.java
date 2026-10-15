package com.hapbang.appearance.app;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.global.exception.BusinessException;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.billing.dto.HoldCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연 요청서의 HP 보류 (보내기의 두 번째·세 번째 단계).
 * <ul>
 *     <li>{@link #requestHold}: Billing에 보류를 요청한다. DB 트랜잭션 밖에서 부른다 (Billing을 기다리는 동안 DB 연결을 잡지 않도록).
 *     Billing이 거절하면 요청서를 {@code HOLD_FAILED}로 남기고 Billing 응답을 그대로 던진다.</li>
 *     <li>{@link #markRequested}: 보류 성공을 요청서에 반영한다. 트랜잭션을 연다. 이번에 {@code REQUESTED}로 바뀐 경우에만
 *     요청 도착 이벤트({@link AppearanceRequestSentEvent})를 발행한다 (ISSUE.md APR-29 N4).</li>
 * </ul>
 * 보류 멱등 키는 Billing이 참조 대상(요청서)으로 만든다. {@code HOLD_PENDING} 보류 재확인 배치({@link AppearanceHoldRecheckUseCase})도 같은 요청서로 이 기능을 다시 쓴다.
 * 거절 기록은 같은 클래스 안 메서드 호출이라 {@code @Transactional}이 적용되지 않아 {@link TransactionTemplate}으로 연다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceRequestHoldUseCase {

    private final BillingClient billingClient;
    private final AppearanceRequestSupport appearanceRequestSupport;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 출연료만큼 호스트의 HP 보류를 요청한다.
     *
     * @throws BusinessException   Billing이 거절했을 때 (예: 409 {@code INSUFFICIENT_BALANCE}). 요청서는 {@code HOLD_FAILED}
     * @throws AppearanceException 처리 결과를 알 수 없을 때 ({@code BILLING_UNAVAILABLE}). 요청서는 {@code HOLD_PENDING}으로 남는다
     */
    public void requestHold(AppearanceRequest request) {
        HoldCommand command = new HoldCommand(request.getHostId(), BillingReferenceType.APPEARANCE_REQUEST,
                request.getId(), request.getFee());
        try {
            billingClient.hold(command);
        } catch (BusinessException rejected) {
            // Billing이 보류를 거절함 (잔액 부족, 지갑 없음 등). 다시 보내도 결과가 같으므로 기록하고 Billing 응답을 그대로 돌려준다.
            log.info("HP 보류 거절, 요청서를 HOLD_FAILED로 둔다 requestId={}, code={}", request.getId(),
                    rejected.getErrorCode().getCode());
            markHoldFailed(request.getId());
            throw rejected;
        } catch (RuntimeException unknown) {
            log.warn("HP 보류 결과 확인 불가, 요청서를 HOLD_PENDING으로 둔다 requestId={}", request.getId(), unknown);
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
        boolean changed = request.markRequested();
        appearanceRequestSupport.flush();
        if (changed) {
            // 보내기와 보류 재확인 배치가 같은 결과를 함께 반영해도 요청 도착은 한 번만 알린다.
            // 받는 쪽은 커밋 뒤에 처리한다 (@ApplicationModuleListener)
            eventPublisher.publishEvent(new AppearanceRequestSentEvent(request.toDto()));
        }
        return AppearanceRequestResDto.from(request);
    }

    /** 보류 거절 → {@code HOLD_FAILED} (기록으로만 남긴다) */
    private void markHoldFailed(Long requestId) {
        transactionTemplate.executeWithoutResult(status -> {
            AppearanceRequest request = appearanceRequestSupport.getById(requestId);
            request.markHoldFailed();
        });
    }
}
