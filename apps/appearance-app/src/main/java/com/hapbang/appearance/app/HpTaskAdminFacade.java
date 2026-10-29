package com.hapbang.appearance.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.HpTaskAdminResDto;
import com.hapbang.appearance.domain.HpTaskStatus;

import lombok.RequiredArgsConstructor;

/**
 * HP 작업 관리 API 진입점 (관리자, #397). 컨트롤러는 이 Facade만 부른다.
 * <ul>
 *     <li>격리된(DEAD) 작업은 업무 상태를 되돌리지 않으므로, 관리자가 원인을 확인한 뒤 같은 작업을 다시 실행하는 것이 복구 경로다.
 *     사용자에게는 다시 시도 기능이 없다.</li>
 *     <li>역할 검사: 경로({@code /api/v1/appearances/admin/**})가 보안 설정에서 관리자만 통과하고, 여기서도 {@code @PreAuthorize}로 확인한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class HpTaskAdminFacade {

    private final HpTaskSupport hpTaskSupport;
    private final HpTaskManualRetryUseCase hpTaskManualRetryUseCase;
    private final HpTaskExecuteUseCase hpTaskExecuteUseCase;

    /** 상태별 HP 작업 목록. 마지막으로 바뀐 작업부터 */
    @Transactional(readOnly = true)
    public Page<HpTaskAdminResDto> getTasks(HpTaskStatus status, Pageable pageable) {
        return hpTaskSupport.findByStatus(status, pageable).map(HpTaskAdminResDto::from);
    }

    /**
     * 격리된 작업 다시 시도.
     * <ol>
     *     <li>[트랜잭션] DEAD → PENDING, 시도 횟수 0, 다시 시도 기록 (커밋)</li>
     *     <li>[트랜잭션 없음] 같은 멱등 키로 1회 실행. 결과는 평소와 같다: 성공 {@code DONE}, Billing 거절 {@code DEAD},
     *     결과를 알 수 없는 실패는 {@code PENDING}으로 남아 재시도 배치가 이어서 실행한다</li>
     * </ol>
     * 다시 실패해도 오류 응답이 아니라, 실행 뒤 작업 상태를 그대로 돌려준다.
     */
    public HpTaskAdminResDto retry(Long adminId, Long taskId) {
        hpTaskManualRetryUseCase.reopen(adminId, taskId);
        hpTaskExecuteUseCase.execute(taskId);
        return HpTaskAdminResDto.from(hpTaskSupport.getById(taskId));
    }
}
