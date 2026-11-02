package com.hapbang.appearance.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceHoldRejectedException;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.HoldResultDto;
import com.hapbang.shared.billing.dto.HoldVoidCommand;
import com.hapbang.shared.billing.dto.HoldVoidResultDto;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연 모집글의 HP 보류 (올리기의 두 번째·세 번째 단계). 출연 요청서 보류({@link AppearanceRequestHoldUseCase})와 같은 흐름이다.
 * <ul>
 *     <li>{@link #requestHold}: 1인당 출연료 × 모집 인원만큼 Billing에 보류를 요청한다. DB 트랜잭션 밖에서 부른다.
 *     지갑이 거절하면 모집글을 {@code HOLD_FAILED}로 남기고 출연 코드로 응답한다({@link BillingHoldRejection}).
 *     예상하지 못한 예외는 요청서 보류({@link AppearanceRequestHoldUseCase})처럼 잡지 않고 500으로 둔다.</li>
 *     <li>{@link #markRecruiting}: 보류 성공을 모집글에 반영한다. 트랜잭션을 연다.</li>
 * </ul>
 * 보류 멱등 키는 Billing이 참조 대상(모집글)으로 만든다. 거절 기록은 같은 클래스 안 메서드 호출이라
 * {@code @Transactional}이 적용되지 않아 {@link TransactionTemplate}으로 연다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceRecruitHoldUseCase {

    private static final String UNAVAILABLE_DETAIL =
            "HP 보류 처리가 지연되고 있습니다. 잠시 후 내 모집글 목록에서 결과를 확인해 주세요.";

    private final BillingClient billingClient;
    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final TransactionTemplate transactionTemplate;

    /**
     * @throws AppearanceHoldRejectedException 지갑이 거절했을 때 (409 {@code INSUFFICIENT_BALANCE} 또는 500 {@code HP_HOLD_FAILED}).
     *                                          모집글은 {@code HOLD_FAILED}
     * @throws AppearanceException              처리 결과를 받지 못했을 때 ({@code BILLING_UNAVAILABLE}). 모집글은 {@code HOLD_PENDING}으로 남는다
     */
    public void requestHold(AppearanceRecruit recruit) {
        HoldCommand command = new HoldCommand(recruit.getHostId(), BillingReferenceType.APPEARANCE_RECRUIT,
                recruit.getId(), recruit.totalFee());
        BillingCallResult<HoldResultDto> result;
        try {
            result = billingClient.hold(command);
        } catch (BillingCallException unknown) {
            log.warn("HP 보류 결과 확인 불가, 모집글을 HOLD_PENDING으로 둔다 recruitId={}, kind={}, httpStatus={}",
                    recruit.getId(), unknown.getKind(), unknown.getHttpStatus(), unknown);
            throw new AppearanceException(AppearanceErrorCode.BILLING_UNAVAILABLE, UNAVAILABLE_DETAIL);
        }
        switch (result) {
            case BillingCallResult.Succeeded<HoldResultDto> held ->
                    log.debug("HP 보류 성공 recruitId={}, replayed={}", recruit.getId(), held.value().replayed());
            case BillingCallResult.Failed<HoldResultDto> rejected -> {
                AppearanceHoldRejectedException exception =
                        BillingHoldRejection.toException(rejected, "모집글", recruit.getId());
                markHoldFailed(recruit.getId());
                throw exception;
            }
        }
    }

    /**
     * 보류 성공 → {@code RECRUITING}. 응답에 담는 버전·수정 시각이 DB와 같도록 바로 반영(flush)한 뒤 응답을 만든다.
     */
    @Transactional
    public AppearanceRecruitResDto markRecruiting(Long recruitId) {
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(recruitId);
        recruit.markRecruiting();
        appearanceRecruitSupport.flush();
        // 방금 올린 모집글이라 지원이 없다
        return AppearanceRecruitResDto.from(recruit, 0);
    }

    /**
     * 보류 결과를 끝내 알 수 없는 모집글을 포기한다 (#536). 흐름은 요청서({@link AppearanceRequestHoldUseCase#voidHold})와 같다.
     *
     * @return 끝냈으면 true, 지갑이 무효화를 거절해 {@code HOLD_PENDING}으로 둔 경우 false (운영 확인 대상)
     * @throws AppearanceException 무효화 결과를 받지 못했을 때 ({@code BILLING_UNAVAILABLE}). 다음 회차에 다시 무효화한다
     */
    public boolean voidHold(AppearanceRecruit recruit) {
        HoldVoidCommand command = new HoldVoidCommand(recruit.getHostId(), BillingReferenceType.APPEARANCE_RECRUIT,
                recruit.getId());
        BillingCallResult<HoldVoidResultDto> result;
        try {
            result = billingClient.voidHold(command);
        } catch (BillingCallException unknown) {
            log.warn("HP 보류 무효화 결과 확인 불가, 모집글을 HOLD_PENDING으로 둔다 recruitId={}, kind={}, httpStatus={}",
                    recruit.getId(), unknown.getKind(), unknown.getHttpStatus(), unknown);
            throw new AppearanceException(AppearanceErrorCode.BILLING_UNAVAILABLE, UNAVAILABLE_DETAIL);
        }
        return switch (result) {
            case BillingCallResult.Succeeded<HoldVoidResultDto> voided -> {
                BillingHoldVoid.logVoided("모집글", recruit.getId(), voided.value());
                markHoldFailed(recruit.getId());
                yield true;
            }
            case BillingCallResult.Failed<HoldVoidResultDto> rejected -> {
                BillingHoldVoid.logRejected("모집글", recruit.getId(), rejected);
                yield false;
            }
        };
    }

    /** 보류 거절·무효화 → {@code HOLD_FAILED} (기록으로만 남긴다) */
    private void markHoldFailed(Long recruitId) {
        transactionTemplate.executeWithoutResult(status -> {
            AppearanceRecruit recruit = appearanceRecruitSupport.getById(recruitId);
            recruit.markHoldFailed();
        });
    }
}
