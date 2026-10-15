package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitSummaryResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.HpTaskStatus;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집글 조회 (공개 목록, 내가 올린 목록, 상세). 트랜잭션은 호출한 {@link AppearanceRecruitFacade}가 연다 (읽기 전용).
 * <ul>
 *     <li>공개 목록·상세: 로그인한 회원이면 역할과 관계없이 볼 수 있다 (AGENTS.md 6장 "모집글 조회").
 *     출연 회원 자격은 확인하지 않는다.</li>
 *     <li>내가 올린 목록: 호스트 본인의 목록이라 출연 회원 자격(정지·강등)을 확인한다.</li>
 *     <li>모든 응답에 지원자 수(취소한 지원만 뺀 전체 지원)를 담는다. 화면에 보일지는 화면이 정한다 (ISSUE.md APR-27 A6).</li>
 *     <li>상세의 마감 반환 진행 상태는 호스트 본인의 돈이라 호스트에게만 담는다 (APR-28 C4).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceRecruitQueryUseCase {

    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceApplicationSupport appearanceApplicationSupport;
    private final HpTaskSupport hpTaskSupport;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /** 공개 목록. 모집 중이고 마감 일시가 지나지 않은 모집글, 최신순 */
    public Page<AppearanceRecruitSummaryResDto> getOpen(Pageable pageable) {
        LocalDateTime now = LocalDateTime.now(clock);
        Page<AppearanceRecruit> recruits = appearanceRecruitSupport.findOpen(now, pageable);
        return toSummaries(recruits);
    }

    /**
     * 내가 올린 모집글 목록 (호스트).
     *
     * @param statuses 상태 필터. 비어 있으면 보이는 상태 전체. 보류 처리 중·실패 상태는 넣을 수 없다
     */
    public Page<AppearanceRecruitSummaryResDto> getMine(Long userId, List<AppearanceRecruitStatus> statuses,
                                                        Pageable pageable) {
        appearanceUserSupport.getParticipant(userId);
        Page<AppearanceRecruit> recruits = appearanceRecruitSupport.findByHost(userId, visibleOnly(statuses),
                pageable);
        return toSummaries(recruits);
    }

    /**
     * 모집글 상세. 보류가 끝나지 않았거나 실패한 모집글은 없는 것으로 응답한다.
     *
     * @throws AppearanceException 없거나 볼 수 없는 모집글({@code APPEARANCE_RECRUIT_NOT_FOUND})
     */
    public AppearanceRecruitResDto getDetail(Long userId, Long recruitId) {
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(recruitId);
        recruit.validateVisible();
        HpTaskStatus hpReleaseStatus = null;
        if (recruit.isHost(userId)) {
            hpReleaseStatus = hpTaskSupport.findReleaseStatusOfRecruit(recruitId);
        }
        return AppearanceRecruitResDto.from(recruit, appearanceApplicationSupport.countApplicants(recruitId),
                hpReleaseStatus);
    }

    /** 한 페이지의 모집글 지원자 수를 한 번에 세어 담는다 (모집글마다 따로 세지 않도록) */
    private Page<AppearanceRecruitSummaryResDto> toSummaries(Page<AppearanceRecruit> recruits) {
        List<Long> recruitIds = new ArrayList<>();
        for (AppearanceRecruit recruit : recruits) {
            recruitIds.add(recruit.getId());
        }
        Map<Long, Long> applicantCounts = appearanceApplicationSupport.countApplicants(recruitIds);
        return recruits.map(recruit -> AppearanceRecruitSummaryResDto.from(recruit,
                applicantCounts.getOrDefault(recruit.getId(), 0L)));
    }

    private List<AppearanceRecruitStatus> visibleOnly(List<AppearanceRecruitStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return AppearanceRecruitStatus.VISIBLE;
        }
        for (AppearanceRecruitStatus status : statuses) {
            if (!status.isVisible()) {
                throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                        "조회할 수 없는 상태입니다: " + status);
            }
        }
        return statuses;
    }
}
