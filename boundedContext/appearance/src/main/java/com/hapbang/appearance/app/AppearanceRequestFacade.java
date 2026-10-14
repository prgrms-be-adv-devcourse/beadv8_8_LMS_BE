package com.hapbang.appearance.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestSummaryResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestContent;
import com.hapbang.appearance.out.billing.BillingClient.HoldResult;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 API 진입점. 컨트롤러는 이 Facade만 부른다.
 * <ul>
 *     <li>역할 검사: 여기서 {@code @PreAuthorize}로 로그인 세션의 역할(인플루언서 이상, 관리자 제외)을 확인한다.
 *     출연 회원 자격(정지·강등)과 업무 규칙은 UseCase·도메인이 확인한다.</li>
 *     <li>트랜잭션: 기능 하나를 한 트랜잭션으로 여기서 연다. 단, 보내기({@link #create})는 Billing 호출을 사이에 두고
 *     "저장 → HP 보류 → 상태 변경"을 따로 커밋해야 해서 여기서 열지 않고, 각 UseCase 단계가 연다.</li>
 * </ul>
 */
// TODO(scheduler): HOLD_PENDING 재확인·만료 스케줄러는 로그인 정보가 없어 @PreAuthorize를 통과할 수 없다.
//  스케줄러용 진입점(역할 검사 없는 Facade)을 따로 두고 같은 UseCase를 쓴다.
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER')")
public class AppearanceRequestFacade {

    private final AppearanceRequestCreateUseCase appearanceRequestCreateUseCase;
    private final AppearanceRequestHoldUseCase appearanceRequestHoldUseCase;
    private final AppearanceRequestQueryUseCase appearanceRequestQueryUseCase;
    private final AppearanceRequestUpdateUseCase appearanceRequestUpdateUseCase;

    /**
     * 출연 요청서를 보내고 출연료만큼 HP를 보류한다. 트랜잭션은 단계마다 따로 열린다.
     * <ol>
     *     <li>[트랜잭션 1] 자격·값·중복 확인 후 {@code HOLD_PENDING}으로 저장 (커밋)</li>
     *     <li>[트랜잭션 없음] Billing에 보류 요청. 무응답이면 {@code HOLD_PENDING}으로 두고 503</li>
     *     <li>[트랜잭션 2] 보류 성공 → {@code REQUESTED}, 잔액 부족 → {@code HOLD_FAILED} + 409</li>
     * </ol>
     */
    public AppearanceRequestResDto create(Long hostId, Long guestId, AppearanceRequestContent content) {
        AppearanceRequest request = appearanceRequestCreateUseCase.createPending(hostId, guestId, content);

        HoldResult holdResult = appearanceRequestHoldUseCase.requestHold(request);

        if (!holdResult.isHeld()) {
            appearanceRequestHoldUseCase.markHoldFailed(request.getId());
            throw new AppearanceException(AppearanceErrorCode.INSUFFICIENT_HP);
        }
        return appearanceRequestHoldUseCase.markRequested(request.getId());
    }

    @Transactional(readOnly = true)
    public Page<AppearanceRequestSummaryResDto> getSent(Long userId, Pageable pageable) {
        return appearanceRequestQueryUseCase.getSent(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AppearanceRequestSummaryResDto> getReceived(Long userId, Pageable pageable) {
        return appearanceRequestQueryUseCase.getReceived(userId, pageable);
    }

    @Transactional(readOnly = true)
    public AppearanceRequestResDto getDetail(Long userId, Long requestId) {
        return appearanceRequestQueryUseCase.getDetail(userId, requestId);
    }

    @Transactional
    public AppearanceRequestResDto updateBasicInfo(Long userId, Long requestId, String title, String content,
                                                   String hostChannelUrl) {
        return appearanceRequestUpdateUseCase.updateBasicInfo(userId, requestId, title, content, hostChannelUrl);
    }
}
