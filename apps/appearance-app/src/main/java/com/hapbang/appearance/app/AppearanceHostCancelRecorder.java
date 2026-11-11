package com.hapbang.appearance.app;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearancePolicy;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.shared.appearance.event.AppearanceCanceledByHostEvent;

import lombok.RequiredArgsConstructor;

/**
 * 출연 1건 호스트 일방 취소와 위약금 정산 작업 기록. 출연 일방 취소와 모집 전체 취소가 함께 쓴다 (ISSUE.md APR-06·APR-32).
 * 트랜잭션은 호출한 쪽이 열고, 출연은 호출한 쪽이 이미 배타 잠금으로 읽어 둔다.
 * <ol>
 *     <li>처리 중인 게스트 취소 요청이 있으면 확인 값을 보고 그 요청을 {@code CLOSED_BY_HOST_CANCEL}로 종료 (H2)</li>
 *     <li>출연 {@code CANCELED_BY_HOST}, 위약금 = 출연료 × 위약금 비율(원 단위 미만 버림)</li>
 *     <li>위약금 정산 작업 기록: 보류에서 위약금은 게스트에게, 나머지는 호스트에게 (커밋 후 실행).
 *     지급 알림용 출처 제목도 함께 남긴다 (#871)</li>
 *     <li>호스트 일방 취소 알림 이벤트 발행 (#871). 롤백되면 나가지 않는다</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AppearanceHostCancelRecorder {

    private final AppearanceCancelRequestSupport appearanceCancelRequestSupport;
    private final HpTaskSupport hpTaskSupport;
    private final AppearancePolicy appearancePolicy;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * @param title 출처 요청서·모집글의 제목 (지급 알림·취소 알림에 쓴다)
     * @return 기록한 위약금 정산 작업 ID
     * @throws AppearanceException {@link Appearance#cancelByHost}와 같다
     */
    public Long cancelAndRecordPenalty(Appearance appearance, String title, Long hostId,
                                       boolean confirmPendingCancelRequest, String reason, LocalDateTime now) {
        boolean hadPendingRequest = appearance.hasPendingCancelRequest();
        long penalty = appearance.cancelByHost(hostId, confirmPendingCancelRequest, reason, appearancePolicy, now);
        if (hadPendingRequest) {
            AppearanceCancelRequest pending = appearanceCancelRequestSupport.getPending(appearance.getId());
            pending.closeByHostCancel(now);
        }
        HpTaskRefType refType = appearance.isFromRecruit()
                ? HpTaskRefType.APPEARANCE_RECRUIT : HpTaskRefType.APPEARANCE_REQUEST;
        Long refId = appearance.isFromRecruit() ? appearance.getRecruitId() : appearance.getRequestId();
        HpTask task = HpTask.penalty(refType, refId, appearance.getHostId(), appearance.getFee(),
                appearance.getGuestId(), penalty, appearance.getId(), title, HpTaskReason.HOST_CANCELED,
                HpTaskSupport.penaltyRequestId(appearance), now);
        Long taskId = hpTaskSupport.save(task).getId();
        eventPublisher.publishEvent(new AppearanceCanceledByHostEvent(
                AppearanceContractMapper.toAppearanceDto(appearance, title), penalty, now));
        return taskId;
    }
}
