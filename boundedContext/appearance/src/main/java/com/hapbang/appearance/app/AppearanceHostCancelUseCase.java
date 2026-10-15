package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceException;

import lombok.RequiredArgsConstructor;

/**
 * 출연 1건 호스트 일방 취소 (#239). 출연은 {@code CANCELED_BY_HOST}, 게스트에게 위약금 30%를 지급하고 나머지는 호스트에게 반환한다.
 * <ul>
 *     <li>잠그는 순서는 모집글 → 출연이다 (모집 출처). 지원 수락·마감·합의 취소·모집 전체 취소와 같은 순서라 교착 상태가 없다.</li>
 *     <li>모집 출처여도 성립 인원은 줄이지 않는다. 그 자리 보류는 위약금·반환으로 정산되어 다시 열 수 없다 (APR-32 H3).</li>
 * </ul>
 * 정산은 Billing 호출이라 커밋 뒤에 실행해야 해서 여기서 트랜잭션을 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceHostCancelUseCase {

    private final AppearanceSupport appearanceSupport;
    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceHostCancelRecorder appearanceHostCancelRecorder;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 기록한 위약금 정산 작업 ID (커밋 후 실행한다)
     * @throws AppearanceException 호스트 자격, 없거나 볼 수 없는 출연, {@link Appearance#cancelByHost}의 오류, 동시 처리 충돌
     */
    @Transactional
    public Long cancel(Long hostId, Long appearanceId, boolean confirmPendingCancelRequest, String reason) {
        appearanceUserSupport.getParticipant(hostId);
        Long recruitId = appearanceSupport.findRecruitIdOf(appearanceId);
        if (recruitId != null) {
            appearanceRecruitSupport.getForUpdate(recruitId);
        }
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        appearance.validateViewableBy(hostId);

        Long taskId = appearanceHostCancelRecorder.cancelAndRecordPenalty(appearance, hostId,
                confirmPendingCancelRequest, reason, LocalDateTime.now(clock));
        appearanceSupport.flushOrConflict();
        return taskId;
    }
}
