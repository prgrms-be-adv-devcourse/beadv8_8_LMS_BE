package com.hapbang.appearance.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 지원 API 진입점. 컨트롤러는 이 Facade만 부른다.
 * <ul>
 *     <li>역할 검사: 지원·취소(게스트)와 수락·거절·지원자 목록(호스트) 모두 인플루언서 이상만 쓸 수 있다 (관리자 제외).
 *     출연 회원 자격(정지·강등)과 업무 규칙은 UseCase·도메인이 확인한다.</li>
 *     <li>트랜잭션: 기능 하나를 한 트랜잭션으로 여기서 연다. Billing 호출이 없어 단계를 나누지 않는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER')")
public class AppearanceApplicationFacade {

    private final AppearanceApplicationApplyUseCase appearanceApplicationApplyUseCase;
    private final AppearanceApplicationWithdrawUseCase appearanceApplicationWithdrawUseCase;
    private final AppearanceApplicationRespondUseCase appearanceApplicationRespondUseCase;
    private final AppearanceApplicationQueryUseCase appearanceApplicationQueryUseCase;

    @Transactional
    public AppearanceApplicationResDto apply(Long guestId, Long recruitId, String message) {
        return appearanceApplicationApplyUseCase.apply(guestId, recruitId, message);
    }

    @Transactional
    public AppearanceApplicationResDto withdraw(Long guestId, Long applicationId) {
        return appearanceApplicationWithdrawUseCase.withdraw(guestId, applicationId);
    }

    /** 수락 (호스트). 지원 {@code SELECTED} + 성립 인원 +1 + 출연 성립이 한 트랜잭션이다. HP는 이동하지 않는다 */
    @Transactional
    public AppearanceApplicationResDto accept(Long hostId, Long applicationId) {
        return appearanceApplicationRespondUseCase.accept(hostId, applicationId);
    }

    @Transactional
    public AppearanceApplicationResDto reject(Long hostId, Long applicationId) {
        return appearanceApplicationRespondUseCase.reject(hostId, applicationId);
    }

    @Transactional(readOnly = true)
    public Page<AppearanceApplicationResDto> getApplicants(Long hostId, Long recruitId,
                                                           List<AppearanceApplicationStatus> statuses,
                                                           Pageable pageable) {
        return appearanceApplicationQueryUseCase.getApplicants(hostId, recruitId, statuses, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AppearanceApplicationResDto> getMine(Long guestId, List<AppearanceApplicationStatus> statuses,
                                                     Pageable pageable) {
        return appearanceApplicationQueryUseCase.getMine(guestId, statuses, pageable);
    }
}
