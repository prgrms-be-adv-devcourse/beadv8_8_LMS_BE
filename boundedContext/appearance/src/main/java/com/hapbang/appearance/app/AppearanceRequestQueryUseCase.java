package com.hapbang.appearance.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestSummaryResDto;
import com.hapbang.appearance.domain.AppearanceRequest;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 조회 (보낸 목록, 받은 목록, 상세). 트랜잭션은 호출한 {@link AppearanceRequestFacade}가 연다 (읽기 전용).
 * 모든 조회에서 출연 회원 자격(정지·강등)을 먼저 확인한다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRequestQueryUseCase {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceUserSupport appearanceUserSupport;

    /** 보낸 요청 목록 (호스트). 보류가 끝나지 않았거나 실패한 요청서는 빠진다 */
    public Page<AppearanceRequestSummaryResDto> getSent(Long userId, Pageable pageable) {
        appearanceUserSupport.getParticipant(userId);
        Page<AppearanceRequest> requests = appearanceRequestSupport.findSent(userId, pageable);
        return requests.map(AppearanceRequestSummaryResDto::from);
    }

    /** 받은 요청 목록 (게스트). 보류가 끝나지 않았거나 실패한 요청서는 빠진다 */
    public Page<AppearanceRequestSummaryResDto> getReceived(Long userId, Pageable pageable) {
        appearanceUserSupport.getParticipant(userId);
        Page<AppearanceRequest> requests = appearanceRequestSupport.findReceived(userId, pageable);
        return requests.map(AppearanceRequestSummaryResDto::from);
    }

    /**
     * 요청서 상세. 호스트·게스트만 볼 수 있다.
     *
     * @throws com.hapbang.appearance.domain.AppearanceException 없거나 볼 수 없는 요청서({@code APPEARANCE_REQUEST_NOT_FOUND})
     */
    public AppearanceRequestResDto getDetail(Long userId, Long requestId) {
        appearanceUserSupport.getParticipant(userId);
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        request.validateViewableBy(userId);
        return AppearanceRequestResDto.from(request);
    }
}
