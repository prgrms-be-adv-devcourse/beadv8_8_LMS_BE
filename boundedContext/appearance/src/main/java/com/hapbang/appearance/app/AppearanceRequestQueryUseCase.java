package com.hapbang.appearance.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestSummaryResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.HpTaskStatus;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 조회 (보낸 목록, 받은 목록, 상세). 트랜잭션은 호출한 {@link AppearanceRequestFacade}가 연다 (읽기 전용).
 * 상세 조회는 스스로 읽기 전용 트랜잭션을 연다: Billing 호출 때문에 트랜잭션 없이 도는 Facade 메서드가 작업 뒤 상세를 다시 읽을 때도
 * 한 시점의 값으로 응답하기 위해서다. Facade 트랜잭션 안에서 부르면 그 트랜잭션에 합류한다 (ISSUE.md APR-37 R1).
 * 모든 조회에서 출연 회원 자격(정지·강등)을 먼저 확인한다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceRequestQueryUseCase {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceSupport appearanceSupport;
    private final HpTaskSupport hpTaskSupport;

    /**
     * 보낸 요청 목록 (호스트).
     *
     * @param statuses 상태 필터. 비어 있으면 보이는 상태 전체. 보류 처리 중·실패 상태는 넣을 수 없다
     */
    public Page<AppearanceRequestSummaryResDto> getSent(Long userId, List<AppearanceRequestStatus> statuses,
                                                        Pageable pageable) {
        appearanceUserSupport.getParticipant(userId);
        Page<AppearanceRequest> requests = appearanceRequestSupport.findSent(userId, visibleOnly(statuses), pageable);
        return requests.map(AppearanceRequestSummaryResDto::from);
    }

    /**
     * 받은 요청 목록 (게스트).
     *
     * @param statuses 상태 필터. 비어 있으면 보이는 상태 전체. 보류 처리 중·실패 상태는 넣을 수 없다
     */
    public Page<AppearanceRequestSummaryResDto> getReceived(Long userId, List<AppearanceRequestStatus> statuses,
                                                            Pageable pageable) {
        appearanceUserSupport.getParticipant(userId);
        Page<AppearanceRequest> requests = appearanceRequestSupport.findReceived(userId, visibleOnly(statuses),
                pageable);
        return requests.map(AppearanceRequestSummaryResDto::from);
    }

    /**
     * 요청서 상세. 호스트·게스트만 볼 수 있다. 수락됐으면 출연 ID, 거절·취소됐으면 반환 진행 상태를 함께 담는다.
     *
     * @throws AppearanceException 없거나 볼 수 없는 요청서({@code APPEARANCE_REQUEST_NOT_FOUND})
     */
    @Transactional(readOnly = true)
    public AppearanceRequestResDto getDetail(Long userId, Long requestId) {
        appearanceUserSupport.getParticipant(userId);
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        request.validateViewableBy(userId);
        Long appearanceId = appearanceSupport.findIdByRequestId(requestId);
        HpTaskStatus hpReleaseStatus = hpTaskSupport.findReleaseStatusOfRequest(requestId);
        return AppearanceRequestResDto.from(request, appearanceId, hpReleaseStatus);
    }

    private List<AppearanceRequestStatus> visibleOnly(List<AppearanceRequestStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return AppearanceRequestStatus.VISIBLE;
        }
        for (AppearanceRequestStatus status : statuses) {
            if (!status.isVisible()) {
                throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                        "조회할 수 없는 상태입니다: " + status);
            }
        }
        return statuses;
    }
}
