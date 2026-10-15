package com.hapbang.appearance.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitSummaryResDto;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitContent;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집글 API 진입점. 컨트롤러는 이 Facade만 부른다.
 * <ul>
 *     <li>역할 검사: 올리기·수정·내 목록은 {@code @PreAuthorize}로 인플루언서 이상(관리자 제외)만 허용한다.
 *     공개 목록·상세는 로그인한 회원이면 누구나 볼 수 있어 역할을 검사하지 않는다 (AGENTS.md 6장).
 *     그래서 요청서 Facade와 달리 메서드마다 붙인다.</li>
 *     <li>트랜잭션: 기능 하나를 한 트랜잭션으로 여기서 연다. 단, 올리기·조기 마감은 Billing 호출이 끼어 저장을 먼저 커밋해야 해서
 *     여기서 열지 않고 각 UseCase 단계가 연다.</li>
 * </ul>
 */
// TODO(security): 비회원 공개 목록은 팀 합의 후 global SecurityConfig에 GET /api/v1/appearances/recruits 허용을 추가한다.
@Service
@RequiredArgsConstructor
public class AppearanceRecruitFacade {

    private static final String INFLUENCER_ONLY = "hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER')";

    private final AppearanceRecruitCreateUseCase appearanceRecruitCreateUseCase;
    private final AppearanceRecruitHoldUseCase appearanceRecruitHoldUseCase;
    private final AppearanceRecruitQueryUseCase appearanceRecruitQueryUseCase;
    private final AppearanceRecruitUpdateUseCase appearanceRecruitUpdateUseCase;
    private final AppearanceRecruitCloseUseCase appearanceRecruitCloseUseCase;
    private final HpTaskExecuteUseCase hpTaskExecuteUseCase;

    /**
     * 출연 모집글을 올리고 1인당 출연료 × 모집 인원만큼 HP를 보류한다. 트랜잭션은 단계마다 따로 열린다.
     * <ol>
     *     <li>[트랜잭션 1] 자격·값 확인 후 {@code HOLD_PENDING}으로 저장 (커밋)</li>
     *     <li>[트랜잭션 없음] Billing에 보류 요청. 결과를 알 수 없으면 {@code HOLD_PENDING}으로 두고 503,
     *     Billing이 거절하면 {@code HOLD_FAILED}로 기록하고 Billing 응답 그대로 (예: 409 {@code INSUFFICIENT_BALANCE})</li>
     *     <li>[트랜잭션 2] 보류 성공 → {@code RECRUITING}</li>
     * </ol>
     */
    @PreAuthorize(INFLUENCER_ONLY)
    public AppearanceRecruitResDto create(Long hostId, AppearanceRecruitContent content) {
        AppearanceRecruit recruit = appearanceRecruitCreateUseCase.createPending(hostId, content);

        appearanceRecruitHoldUseCase.requestHold(recruit);

        return appearanceRecruitHoldUseCase.markRecruiting(recruit.getId());
    }

    @Transactional(readOnly = true)
    public Page<AppearanceRecruitSummaryResDto> getOpen(Pageable pageable) {
        return appearanceRecruitQueryUseCase.getOpen(pageable);
    }

    @PreAuthorize(INFLUENCER_ONLY)
    @Transactional(readOnly = true)
    public Page<AppearanceRecruitSummaryResDto> getMine(Long userId, List<AppearanceRecruitStatus> statuses,
                                                        Pageable pageable) {
        return appearanceRecruitQueryUseCase.getMine(userId, statuses, pageable);
    }

    @Transactional(readOnly = true)
    public AppearanceRecruitResDto getDetail(Long userId, Long recruitId) {
        return appearanceRecruitQueryUseCase.getDetail(userId, recruitId);
    }

    @PreAuthorize(INFLUENCER_ONLY)
    @Transactional
    public AppearanceRecruitResDto updateBasicInfo(Long userId, Long recruitId, String title, String content,
                                                   String hostChannelUrl) {
        return appearanceRecruitUpdateUseCase.updateBasicInfo(userId, recruitId, title, content, hostChannelUrl);
    }

    /**
     * 조기 마감 (호스트). 성립 인원이 0명이어도 할 수 있다.
     * <ol>
     *     <li>[트랜잭션 1] 모집글 {@code CLOSED}(조기 마감) + 대기 중 지원 미선정 + 성립하지 못한 인원분 반환 작업 기록 (커밋)</li>
     *     <li>[트랜잭션 없음] 반환 작업 1회 실행. 실패해도 마감은 성공으로 응답하고, 작업은 대기로 남는다.
     *     정원이 다 차서 돌려줄 금액이 없으면 건너뛴다</li>
     * </ol>
     */
    @PreAuthorize(INFLUENCER_ONLY)
    public AppearanceRecruitResDto closeEarly(Long hostId, Long recruitId) {
        Long releaseTaskId = appearanceRecruitCloseUseCase.closeEarly(hostId, recruitId);
        if (releaseTaskId != null) {
            hpTaskExecuteUseCase.execute(releaseTaskId);
        }
        return appearanceRecruitQueryUseCase.getDetail(hostId, recruitId);
    }
}
