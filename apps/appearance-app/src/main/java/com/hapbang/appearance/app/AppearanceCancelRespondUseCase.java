package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestRejectedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 출연 취소 요청 응답 (호스트): 수락(합의 취소)·거절.
 * <ul>
 *     <li>수락하면 출연은 {@code CANCELED_BY_AGREEMENT}(위약금 없음). HP는 출처에 따라 다르다 (AGENTS.md, ISSUE.md APR-31 K4)
 *     <ul>
 *         <li>요청서: 출연료 전액을 호스트에게 반환 (HP 작업 기록, 커밋 후 실행)</li>
 *         <li>모집, 모집글이 아직 마감 전: 성립 인원 −1, 보류 유지 → 빈자리에 다른 지원자를 수락할 수 있고, 다시 채우지 않으면 마감 때 함께 반환</li>
 *         <li>모집, 모집글이 마감됨: 그 자리 출연료(1인분)를 바로 반환</li>
 *     </ul></li>
 *     <li>거절하면 출연은 {@code ESTABLISHED}로 돌아오고, 게스트는 1회 더 요청할 수 있다.</li>
 *     <li>잠그는 순서는 모집글 → 출연이다. 모집글의 수락·마감(APR-28 C9)과 같은 순서라 교착 상태가 없다.
 *     출연 행은 배타 잠금이라 수락·거절·자동 거절이 겹치면 차례로 처리되고 나중 것은 {@code CANCEL_REQUEST_NOT_PENDING}이다.</li>
 *     <li>결과를 게스트에게 알리는 이벤트를 발행한다 (K7).</li>
 * </ul>
 * 수락은 반환을 커밋 뒤에 실행해야 해서 여기서 트랜잭션을 연다. 거절은 호출한 Facade가 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceCancelRespondUseCase {

    private final AppearanceSupport appearanceSupport;
    private final AppearanceCancelRequestSupport appearanceCancelRequestSupport;
    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceTitleSupport appearanceTitleSupport;
    private final HpTaskSupport hpTaskSupport;
    private final ApplicationEventPublisher eventPublisher;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 기록한 반환 작업 ID (커밋 후 실행한다). 모집글이 아직 마감 전이라 반환하지 않으면 null
     * @throws AppearanceException 호스트 자격, 없거나 볼 수 없는 출연, 호스트가 아님({@code NOT_APPEARANCE_HOST}),
     *                             처리 중인 요청 없음({@code CANCEL_REQUEST_NOT_PENDING}), 방송 예정 일시 지남, 동시 처리 충돌
     */
    @Transactional
    public Long accept(Long hostId, Long appearanceId) {
        appearanceUserSupport.getParticipant(hostId);
        LocalDateTime now = LocalDateTime.now(clock);
        // 모집에서 성립한 출연이면 모집글을 먼저 잠근다 (모집글 → 출연 순서)
        Long recruitId = appearanceSupport.findRecruitIdOf(appearanceId);
        AppearanceRecruit recruit = null;
        if (recruitId != null) {
            recruit = appearanceRecruitSupport.getForUpdate(recruitId);
        }
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        appearance.validateViewableBy(hostId);
        appearance.validateCancelRespondable(hostId, now);

        AppearanceCancelRequest cancelRequest = appearanceCancelRequestSupport.getPending(appearanceId);
        cancelRequest.accept(now);
        appearance.agreeCancel(now);
        HpTask releaseTask = releaseOnAgreement(appearance, recruit, now);
        appearanceSupport.flushOrConflict();

        Long releaseTaskId = null;
        if (releaseTask != null) {
            releaseTaskId = hpTaskSupport.save(releaseTask).getId();
        }
        eventPublisher.publishEvent(new AppearanceCancelRequestAcceptedEvent(
                cancelRequest.toDto(appearanceTitleSupport.titleOf(appearance), appearance.getScheduledAt())));
        return releaseTaskId;
    }

    /**
     * @param rejectReason 거절 사유 (선택, 500자 이하)
     * @throws AppearanceException 호스트 자격, 없거나 볼 수 없는 출연, 호스트가 아님, 처리 중인 요청 없음, 방송 예정 일시 지남,
     *                             거절 사유 500자 초과({@code INVALID_REQUEST}), 동시 처리 충돌
     */
    public void reject(Long hostId, Long appearanceId, String rejectReason) {
        appearanceUserSupport.getParticipant(hostId);
        LocalDateTime now = LocalDateTime.now(clock);
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        appearance.validateViewableBy(hostId);
        appearance.validateCancelRespondable(hostId, now);

        AppearanceCancelRequest cancelRequest = appearanceCancelRequestSupport.getPending(appearanceId);
        cancelRequest.reject(rejectReason, now);
        appearance.keepAfterCancelRejected();
        appearanceSupport.flushOrConflict();

        eventPublisher.publishEvent(new AppearanceCancelRequestRejectedEvent(
                cancelRequest.toDto(appearanceTitleSupport.titleOf(appearance), appearance.getScheduledAt()), false));
    }

    /** 합의 취소로 돌려줄 HP 작업. 모집글이 마감 전이면 성립 인원만 줄이고 반환하지 않는다(null) */
    private HpTask releaseOnAgreement(Appearance appearance, AppearanceRecruit recruit, LocalDateTime now) {
        if (recruit == null) {
            return release(HpTaskRefType.APPEARANCE_REQUEST, appearance.getRequestId(), appearance, now);
        }
        recruit.decreaseSelected();
        if (!recruit.isClosed()) {
            return null;
        }
        return release(HpTaskRefType.APPEARANCE_RECRUIT, recruit.getId(), appearance, now);
    }

    /** 보류(요청서 또는 모집글)에서 이 출연의 출연료를 호스트에게 반환하는 작업. 반환 요청 ID는 출연 단위다 */
    private HpTask release(HpTaskRefType refType, Long refId, Appearance appearance, LocalDateTime now) {
        String releaseRequestId = HpTask.appearanceRequestId(refType, refId, HpTaskType.RELEASE, appearance.getId());
        return HpTask.release(refType, refId, appearance.getHostId(), appearance.getFee(), HpTaskReason.CANCEL_AGREED,
                releaseRequestId, now);
    }
}
