package com.hapbang.appearance.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.HpReconciliationRunResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.HpReconciliationRun;

import lombok.RequiredArgsConstructor;

/**
 * 서비스 간 HP 대사 관리 API 진입점 (관리자, #581). 컨트롤러는 이 Facade만 부른다.
 * <p>
 * 관리자 API 3중 방어는 HP 작업 관리({@link HpTaskAdminFacade})와 같다: 보안 설정 → {@code @PreAuthorize} → 복제한 회원 정보로
 * 지금도 정상 상태의 관리자인지 확인.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class HpReconciliationAdminFacade {

    private final AppearanceUserSupport appearanceUserSupport;
    private final HpReconciliationUseCase hpReconciliationUseCase;
    private final HpReconciliationSupport hpReconciliationSupport;

    /**
     * 지금 대사를 실행하고 끝날 때까지 기다려 결과를 돌려준다. 지갑 조회가 실패해도 오류 응답이 아니라 {@code INCOMPLETE} 결과다.
     * 지갑을 부르므로 트랜잭션을 열지 않는다.
     *
     * @throws AppearanceException 관리자 자격 없음, 이미 실행 중({@code HP_RECONCILIATION_RUNNING})
     */
    public HpReconciliationRunResDto run(Long adminId) {
        appearanceUserSupport.getAdmin(adminId);
        HpReconciliationRun run = hpReconciliationUseCase.runManual(adminId);
        return HpReconciliationRunResDto.from(run, hpReconciliationSupport.findMismatches(run.getId()));
    }

    /**
     * 마지막으로 시작한 실행과 그 어긋난 건. 실행 중이면 그때까지의 상태({@code RUNNING}, 어긋난 건 없음)다.
     *
     * @throws AppearanceException 관리자 자격 없음, 실행 기록 없음({@code HP_RECONCILIATION_NOT_FOUND})
     */
    public HpReconciliationRunResDto getLatest(Long adminId) {
        appearanceUserSupport.getAdmin(adminId);
        HpReconciliationRun run = hpReconciliationSupport.findLatestRun();
        if (run == null) {
            throw new AppearanceException(AppearanceErrorCode.HP_RECONCILIATION_NOT_FOUND);
        }
        return HpReconciliationRunResDto.from(run, hpReconciliationSupport.findMismatches(run.getId()));
    }
}
