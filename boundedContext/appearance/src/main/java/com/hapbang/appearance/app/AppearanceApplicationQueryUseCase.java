package com.hapbang.appearance.app;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.domain.AppearanceApplication;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 지원 조회: 모집글의 지원자 목록(호스트만), 내 지원 목록(게스트). 트랜잭션은 호출한 Facade가 연다 (읽기 전용).
 * 수락된 지원에는 성립한 출연 ID를 함께 담는다 (한 페이지를 한 번에 조회).
 */
@Service
@RequiredArgsConstructor
public class AppearanceApplicationQueryUseCase {

    private final AppearanceApplicationSupport appearanceApplicationSupport;
    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceUserSupport appearanceUserSupport;
    private final AppearanceSupport appearanceSupport;

    /**
     * 모집글의 지원자 목록. 모집글을 올린 호스트만 볼 수 있다 (다른 회원에게는 지원자 수만 보인다).
     *
     * @param statuses 상태 필터. 비어 있으면 전체
     * @throws AppearanceException 호스트 자격, 없거나 보이지 않는 모집글, 호스트가 아님({@code NOT_RECRUIT_HOST})
     */
    public Page<AppearanceApplicationResDto> getApplicants(Long userId, Long recruitId,
                                                           List<AppearanceApplicationStatus> statuses,
                                                           Pageable pageable) {
        appearanceUserSupport.getParticipant(userId);
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(recruitId);
        recruit.validateVisible();
        if (!recruit.isHost(userId)) {
            throw new AppearanceException(AppearanceErrorCode.NOT_RECRUIT_HOST,
                    "출연 모집글을 올린 호스트만 지원자 목록을 볼 수 있습니다.");
        }
        return withAppearanceIds(appearanceApplicationSupport.findByRecruit(recruitId, orAll(statuses), pageable));
    }

    /**
     * 내 지원 목록 (게스트).
     *
     * @param statuses 상태 필터. 비어 있으면 전체
     */
    public Page<AppearanceApplicationResDto> getMine(Long userId, List<AppearanceApplicationStatus> statuses,
                                                     Pageable pageable) {
        appearanceUserSupport.getParticipant(userId);
        return withAppearanceIds(appearanceApplicationSupport.findByGuest(userId, orAll(statuses), pageable));
    }

    private Page<AppearanceApplicationResDto> withAppearanceIds(Page<AppearanceApplication> applications) {
        List<Long> applicationIds = applications.map(AppearanceApplication::getId).getContent();
        Map<Long, Long> appearanceIds = appearanceSupport.findIdsByApplicationIds(applicationIds);
        return applications.map(application -> AppearanceApplicationResDto.from(application,
                appearanceIds.get(application.getId())));
    }

    private static List<AppearanceApplicationStatus> orAll(List<AppearanceApplicationStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of(AppearanceApplicationStatus.values());
        }
        return statuses;
    }
}
