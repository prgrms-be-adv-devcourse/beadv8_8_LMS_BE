package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.HpTask;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 다시 시도의 첫 단계 (#397). 격리된(DEAD) HP 작업을 대기로 되돌리고 바로 커밋한다.
 * <ul>
 *     <li>실행은 커밋 뒤 {@link HpTaskExecuteUseCase}가 트랜잭션 밖에서 한다 (업무 처리 직후 실행과 같은 흐름).</li>
 *     <li>두 관리자가 동시에 다시 시도하면 버전(낙관적 락)이 맞지 않는 쪽을 {@code CONCURRENT_UPDATE}(409)로 막는다.
 *     커밋 때 충돌이 나면 공통 핸들러가 500으로 응답하므로 메서드 안에서 미리 반영해 본다.</li>
 *     <li>다시 시도하면 시도 횟수가 0으로 돌아가므로, 직전 시도 횟수·오류를 {@code [HP_TASK_MANUAL_RETRY]} 로그로 남긴다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HpTaskManualRetryUseCase {

    private final AppearanceUserSupport appearanceUserSupport;
    private final HpTaskSupport hpTaskSupport;

    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * 관리자 자격(정상 상태의 관리자)을 먼저 확인한다 (관리자 API 서비스 단 검증).
     *
     * @throws com.hapbang.appearance.domain.AppearanceException 관리자 자격 없음({@code APPEARANCE_USER_NOT_FOUND},
     *                                                          {@code APPEARANCE_USER_NOT_ACTIVE}, {@code NOT_ADMIN}),
     *                                                          작업 없음({@code HP_TASK_NOT_FOUND}),
     *                                                          격리된 작업이 아님({@code HP_TASK_NOT_DEAD}),
     *                                                          동시 처리 충돌({@code CONCURRENT_UPDATE})
     */
    @Transactional
    public void reopen(Long adminId, Long taskId) {
        appearanceUserSupport.getAdmin(adminId);
        HpTask task = hpTaskSupport.getForAdmin(taskId);
        int previousAttempts = task.getAttemptCount();
        String previousError = task.getLastError();
        task.reopenForManualRetry(adminId, LocalDateTime.now(clock));
        hpTaskSupport.flushOrConflict();
        log.warn("[HP_TASK_MANUAL_RETRY] taskId={}, adminId={}, type={}, ref={}-{}, amount={}, manualRetryCount={}, "
                        + "previousAttempts={}, previousError={}",
                task.getId(), adminId, task.getTaskType(), task.getRefType(), task.getRefId(), task.getAmount(),
                task.getManualRetryCount(), previousAttempts, previousError);
    }
}
