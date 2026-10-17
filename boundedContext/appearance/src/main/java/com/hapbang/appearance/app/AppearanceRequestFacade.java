package com.hapbang.appearance.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestSummaryResDto;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestContent;
import com.hapbang.appearance.domain.AppearanceRequestStatus;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 API 진입점. 컨트롤러는 이 Facade만 부른다.
 * <ul>
 *     <li>역할 검사: 여기서 {@code @PreAuthorize}로 로그인 세션의 역할(인플루언서 이상, 관리자 제외)을 확인한다.
 *     출연 회원 자격(정지·강등)과 업무 규칙은 UseCase·도메인이 확인한다.</li>
 *     <li>트랜잭션: 기능 하나를 한 트랜잭션으로 여기서 연다. 단, Billing 호출이 끼는 기능(보내기, 거절, 응답 전 취소)은
 *     업무 상태를 먼저 커밋하고 Billing을 불러야 해서 여기서 열지 않고, 각 UseCase 단계가 연다.</li>
 * </ul>
 */
// 배치(보류 재확인·수락 기한 만료·HP 작업 재시도)는 로그인 회원이 아니라 시스템이 실행하므로 이 Facade를 거치지 않고
// in.AppearanceBatch가 UseCase를 직접 부른다 (팀 강의 배치와 같은 방식, ISSUE.md APR-26).
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER')")
public class AppearanceRequestFacade {

    private final AppearanceRequestCreateUseCase appearanceRequestCreateUseCase;
    private final AppearanceRequestHoldUseCase appearanceRequestHoldUseCase;
    private final AppearanceRequestQueryUseCase appearanceRequestQueryUseCase;
    private final AppearanceRequestUpdateUseCase appearanceRequestUpdateUseCase;
    private final AppearanceRequestAcceptUseCase appearanceRequestAcceptUseCase;
    private final AppearanceRequestRejectUseCase appearanceRequestRejectUseCase;
    private final AppearanceRequestCancelUseCase appearanceRequestCancelUseCase;
    private final HpTaskExecuteUseCase hpTaskExecuteUseCase;

    /**
     * 출연 요청서를 보내고 출연료만큼 HP를 보류한다. 트랜잭션은 단계마다 따로 열린다.
     * <ol>
     *     <li>[트랜잭션 1] 자격·값·중복 확인 후 {@code HOLD_PENDING}으로 저장 (커밋)</li>
     *     <li>[트랜잭션 없음] Billing에 보류 요청. 결과를 알 수 없으면 {@code HOLD_PENDING}으로 두고 503,
     *     Billing이 거절하면(잔액 부족 등) {@code HOLD_FAILED}로 기록하고 Billing 응답 그대로 (예: 409 {@code INSUFFICIENT_BALANCE})</li>
     *     <li>[트랜잭션 2] 보류 성공 → {@code REQUESTED}</li>
     * </ol>
     */
    public AppearanceRequestResDto create(Long hostId, Long guestId, AppearanceRequestContent content) {
        AppearanceRequest request = appearanceRequestCreateUseCase.createPending(hostId, guestId, content);

        appearanceRequestHoldUseCase.requestHold(request);

        return appearanceRequestHoldUseCase.markRequested(request.getId());
    }

    @Transactional(readOnly = true)
    public Page<AppearanceRequestSummaryResDto> getSent(Long userId, List<AppearanceRequestStatus> statuses,
                                                        Pageable pageable) {
        return appearanceRequestQueryUseCase.getSent(userId, statuses, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AppearanceRequestSummaryResDto> getReceived(Long userId, List<AppearanceRequestStatus> statuses,
                                                            Pageable pageable) {
        return appearanceRequestQueryUseCase.getReceived(userId, statuses, pageable);
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

    /**
     * 수락 (게스트). 요청서 {@code ACCEPTED} + 출연 성립이 한 트랜잭션이다. HP는 이동하지 않는다.
     */
    @Transactional
    public AppearanceRequestResDto accept(Long guestId, Long requestId, Long viewedVersion) {
        Appearance appearance = appearanceRequestAcceptUseCase.accept(guestId, requestId, viewedVersion);
        return appearanceRequestQueryUseCase.getDetail(guestId, appearance.getRequestId());
    }

    /**
     * 거절 (게스트).
     * <ol>
     *     <li>[트랜잭션 1] 요청서 {@code REJECTED} + 반환 작업 기록 (커밋)</li>
     *     <li>[트랜잭션 없음] 반환 작업 1회 실행. 실패해도 거절은 성공으로 응답하고, 작업은 대기로 남는다</li>
     * </ol>
     */
    public AppearanceRequestResDto reject(Long guestId, Long requestId, String reason) {
        Long taskId = appearanceRequestRejectUseCase.reject(guestId, requestId, reason);
        hpTaskExecuteUseCase.execute(taskId);
        return appearanceRequestQueryUseCase.getDetail(guestId, requestId);
    }

    /**
     * 응답 전 취소 (호스트). 흐름은 거절과 같다.
     */
    public AppearanceRequestResDto cancel(Long hostId, Long requestId) {
        Long taskId = appearanceRequestCancelUseCase.cancel(hostId, requestId);
        hpTaskExecuteUseCase.execute(taskId);
        return appearanceRequestQueryUseCase.getDetail(hostId, requestId);
    }
}
