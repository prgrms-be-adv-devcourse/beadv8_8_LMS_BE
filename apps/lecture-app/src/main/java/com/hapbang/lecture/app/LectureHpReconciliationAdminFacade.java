package com.hapbang.lecture.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.LectureHpReconciliationRunResponse;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureHpReconciliationRun;

import lombok.RequiredArgsConstructor;

/**
 * 관리자의 서비스 간 HP 대사 실행·결과 조회 진입점 (D74). 관리자 여부는 여기서 검사한다.
 * /admin 경로는 SecurityConfig도 함께 막는다 (D16). 지갑을 부르므로 트랜잭션을 열지 않는다.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class LectureHpReconciliationAdminFacade {

    private final LectureHpReconciliationUseCase lectureHpReconciliationUseCase;
    private final LectureHpReconciliationSupport lectureHpReconciliationSupport;

    /**
     * 지금 대사를 실행하고 끝날 때까지 기다려 결과를 돌려준다. 지갑 조회가 실패해도 오류 응답이 아니라 {@code INCOMPLETE} 결과다.
     *
     * @throws LectureException 이미 실행 중({@code HP_RECONCILIATION_RUNNING})
     */
    public LectureHpReconciliationRunResponse run(Long adminUserId) {
        LectureHpReconciliationRun run = lectureHpReconciliationUseCase.runManual(adminUserId);
        return LectureHpReconciliationRunResponse.of(run, lectureHpReconciliationSupport.findMismatches(run.getId()));
    }

    /**
     * 마지막으로 시작한 실행과 그 어긋난 건. 실행 중이면 그때까지의 상태({@code RUNNING}, 어긋난 건 없음)다.
     *
     * @throws LectureException 실행 기록 없음({@code HP_RECONCILIATION_NOT_FOUND})
     */
    public LectureHpReconciliationRunResponse getLatest() {
        LectureHpReconciliationRun run = lectureHpReconciliationSupport.findLatestRun();
        if (run == null) {
            throw new LectureException(LectureErrorCode.HP_RECONCILIATION_NOT_FOUND);
        }
        return LectureHpReconciliationRunResponse.of(run, lectureHpReconciliationSupport.findMismatches(run.getId()));
    }
}
