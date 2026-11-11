package com.hapbang.appearance.app;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.shared.appearance.event.AppearanceAbsentEvent;
import com.hapbang.shared.appearance.event.AppearanceConfirmedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 출연 확인 결과의 HP 작업 기록 (#282). 호스트 확인·자동 확인·불참이 함께 쓴다. 트랜잭션은 호출한 쪽이 열고,
 * 출연은 호출한 쪽이 이미 배타 잠금으로 읽고 상태를 바꿔 둔다.
 * <ul>
 *     <li>확인 → 지급 작업: 보류에서 출연료 전부를 게스트에게 (요청 ID {@code …-PAYOUT-APPEARANCE-{출연 ID}})</li>
 *     <li>불참 → 반환 작업: 보류에서 출연료를 호스트에게 (요청 ID {@code …-RELEASE-APPEARANCE-{출연 ID}}, 합의 취소와 같은 형식)</li>
 * </ul>
 * 출연 하나는 확인·불참·취소 중 하나로만 끝나므로 같은 출연에 지급과 반환이 함께 생기지 않는다.
 * 기록과 함께 확인·불참 알림 이벤트를 낸다 (#871). 지급 작업에는 지급 알림용 출처 제목을 남긴다.
 * 롤백되면 이벤트는 나가지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceConfirmRecorder {

    private final HpTaskSupport hpTaskSupport;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * @param title 출처 요청서·모집글의 제목
     * @return 기록한 지급 작업 ID (커밋 후 실행한다)
     */
    public Long recordPayout(Appearance appearance, String title, LocalDateTime now) {
        HpTask task = HpTask.payout(appearance.holdRefType(), appearance.holdRefId(), appearance.getHostId(),
                appearance.getFee(), appearance.getGuestId(), appearance.getId(), title,
                HpTaskReason.APPEARANCE_CONFIRMED, HpTaskSupport.payoutRequestId(appearance), now);
        Long taskId = hpTaskSupport.save(task).getId();
        eventPublisher.publishEvent(new AppearanceConfirmedEvent(AppearanceContractMapper.toAppearanceDto(appearance,
                title), AppearanceContractMapper.toConfirmType(appearance), now));
        return taskId;
    }

    /**
     * @param title 출처 요청서·모집글의 제목
     * @return 기록한 반환 작업 ID (커밋 후 실행한다)
     */
    public Long recordAbsentRelease(Appearance appearance, String title, LocalDateTime now) {
        HpTask task = HpTask.release(appearance.holdRefType(), appearance.holdRefId(), appearance.getHostId(),
                appearance.getFee(), HpTaskReason.APPEARANCE_ABSENT, HpTaskSupport.releaseRequestId(appearance), now);
        Long taskId = hpTaskSupport.save(task).getId();
        eventPublisher.publishEvent(new AppearanceAbsentEvent(AppearanceContractMapper.toAppearanceDto(appearance,
                title), now));
        return taskId;
    }
}
