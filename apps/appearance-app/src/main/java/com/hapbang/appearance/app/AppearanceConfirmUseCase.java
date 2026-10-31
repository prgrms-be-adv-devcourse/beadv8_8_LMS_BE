package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearancePolicy;

import lombok.RequiredArgsConstructor;

/**
 * 호스트의 출연 확인 응답 (#282): 확인 → 게스트에게 출연료 지급, 불참 → 호스트에게 출연료 반환.
 * <ul>
 *     <li>응답은 방송 예정 일시부터 확인 기간(D+7) 전까지 한 번만 한다. 번복할 수 없다 (ISSUE.md APR-35 E3).</li>
 *     <li>출연 회원 자격(정지·강등)은 확인하지 않는다. 거래를 마무리하는 동작이라 정지된 호스트도 응답할 수 있다 (E5).</li>
 *     <li>출연 행만 배타 잠금으로 읽는다. 같은 출연의 자동 확인 배치와 차례로 처리되고, 나중 처리는 바뀐 상태를 보고 멈춘다.
 *     모집글은 잠그지 않는다: 방송 예정 일시가 지나면 모집글을 바꾸는 처리(지원 수락·마감·합의 취소·모집 전체 취소)가 모두 막혀 있다.</li>
 * </ul>
 * HP 작업은 Billing 호출이라 커밋 뒤에 실행해야 해서 여기서 트랜잭션을 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceConfirmUseCase {

    private final AppearanceSupport appearanceSupport;
    private final AppearanceConfirmRecorder appearanceConfirmRecorder;
    private final AppearancePolicy appearancePolicy;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * 출연 확인 → {@code CONFIRMED}(호스트 확인) + 지급 작업 기록.
     *
     * @return 기록한 지급 작업 ID (커밋 후 실행한다)
     * @throws AppearanceException 없거나 볼 수 없는 출연({@code APPEARANCE_NOT_FOUND}), {@link Appearance#validateConfirmable}의 오류,
     *                             동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    @Transactional
    public Long confirm(Long hostId, Long appearanceId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Appearance appearance = lockViewable(hostId, appearanceId);
        appearance.confirmByHost(hostId, appearancePolicy, now);
        Long taskId = appearanceConfirmRecorder.recordPayout(appearance, now);
        appearanceSupport.flushOrConflict();
        return taskId;
    }

    /**
     * 불참 → {@code ABSENT} + 반환 작업 기록.
     *
     * @param reason 사유 (선택, 500자 이하)
     * @return 기록한 반환 작업 ID (커밋 후 실행한다)
     * @throws AppearanceException {@link #confirm}과 같고, 사유 500자 초과({@code INVALID_REQUEST})
     */
    @Transactional
    public Long markAbsent(Long hostId, Long appearanceId, String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        Appearance appearance = lockViewable(hostId, appearanceId);
        appearance.markAbsent(hostId, reason, appearancePolicy, now);
        Long taskId = appearanceConfirmRecorder.recordAbsentRelease(appearance, now);
        appearanceSupport.flushOrConflict();
        return taskId;
    }

    /** 출연을 배타 잠금으로 읽는다. 호스트·게스트가 아니면 있다는 사실도 알리지 않는다 */
    private Appearance lockViewable(Long userId, Long appearanceId) {
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        appearance.validateViewableBy(userId);
        return appearance;
    }
}
