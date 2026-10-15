package com.hapbang.appearance.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.app.dto.AppearanceSummaryResDto;
import com.hapbang.appearance.domain.AppearanceRole;
import com.hapbang.appearance.domain.AppearanceStatus;

import lombok.RequiredArgsConstructor;

/**
 * 출연(성립 건) API 진입점: 내 출연 조회, 출연 취소 요청·응답 (#231). 컨트롤러는 이 Facade만 부른다.
 * <ul>
 *     <li>역할 검사: 모든 기능이 인플루언서 이상(관리자 제외)이라 클래스에 {@code @PreAuthorize}를 붙인다.</li>
 *     <li>트랜잭션: 기능 하나를 한 트랜잭션으로 여기서 연다. 단, 취소 요청 수락·호스트 일방 취소는 반환·위약금 정산(Billing)을
 *     커밋 뒤에 실행해야 해서 UseCase가 연다.</li>
 *     <li>배치(자동 거절)는 Facade를 거치지 않고 UseCase를 직접 부른다 (ISSUE.md APR-26).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER')")
public class AppearanceFacade {

    private final AppearanceQueryUseCase appearanceQueryUseCase;
    private final AppearanceCancelRequestUseCase appearanceCancelRequestUseCase;
    private final AppearanceCancelRespondUseCase appearanceCancelRespondUseCase;
    private final AppearanceHostCancelUseCase appearanceHostCancelUseCase;
    private final HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @Transactional(readOnly = true)
    public Page<AppearanceSummaryResDto> getMine(Long userId, AppearanceRole role, List<AppearanceStatus> statuses,
                                                 Pageable pageable) {
        return appearanceQueryUseCase.getMine(userId, role, statuses, pageable);
    }

    @Transactional(readOnly = true)
    public AppearanceResDto getDetail(Long userId, Long appearanceId) {
        return appearanceQueryUseCase.getDetail(userId, appearanceId);
    }

    /** 출연 취소 요청 (게스트). 응답은 요청이 반영된 출연 상세다 */
    @Transactional
    public AppearanceResDto requestCancel(Long guestId, Long appearanceId, String reason) {
        appearanceCancelRequestUseCase.request(guestId, appearanceId, reason);
        return appearanceQueryUseCase.getDetail(guestId, appearanceId);
    }

    /**
     * 출연 취소 요청 수락 (호스트, 합의 취소).
     * <ol>
     *     <li>[트랜잭션 1] 출연 {@code CANCELED_BY_AGREEMENT} + (반환할 때) 반환 작업 기록 (커밋)</li>
     *     <li>[트랜잭션 없음] 반환 작업 1회 실행. 실패해도 수락은 성공으로 응답하고, 작업은 재시도 대기로 남는다</li>
     * </ol>
     */
    public AppearanceResDto acceptCancel(Long hostId, Long appearanceId) {
        Long releaseTaskId = appearanceCancelRespondUseCase.accept(hostId, appearanceId);
        if (releaseTaskId != null) {
            hpTaskExecuteUseCase.execute(releaseTaskId);
        }
        return appearanceQueryUseCase.getDetail(hostId, appearanceId);
    }

    /** 출연 취소 요청 거절 (호스트). 출연은 유지된다 */
    @Transactional
    public AppearanceResDto rejectCancel(Long hostId, Long appearanceId, String rejectReason) {
        appearanceCancelRespondUseCase.reject(hostId, appearanceId, rejectReason);
        return appearanceQueryUseCase.getDetail(hostId, appearanceId);
    }

    /**
     * 호스트 일방 취소 (#239). 게스트에게 위약금 30%, 나머지는 호스트에게.
     * <ol>
     *     <li>[트랜잭션 1] 출연 {@code CANCELED_BY_HOST} (+ 처리 중인 취소 요청 종료) + 위약금 정산 작업 기록 (커밋)</li>
     *     <li>[트랜잭션 없음] 위약금 정산 1회 실행. 실패해도 취소는 성공으로 응답하고, 작업은 재시도 대기로 남는다</li>
     * </ol>
     */
    public AppearanceResDto hostCancel(Long hostId, Long appearanceId, boolean confirmPendingCancelRequest,
                                       String reason) {
        Long penaltyTaskId = appearanceHostCancelUseCase.cancel(hostId, appearanceId, confirmPendingCancelRequest,
                reason);
        hpTaskExecuteUseCase.execute(penaltyTaskId);
        return appearanceQueryUseCase.getDetail(hostId, appearanceId);
    }
}
