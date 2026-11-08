package com.hapbang.appearance.in;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceRecruitInternalFacade;
import com.hapbang.appearance.app.dto.AppearanceRecruitApplicantIdsResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitAppliedUserIdsResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitInternalResDto;
import com.hapbang.appearance.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집글 내부 API (#586). Swagger 명세는 {@link ApiV1AppearanceRecruitInternalSpec}, 계약은 docs/appearance/module.md 24장.
 * 로직은 {@link AppearanceRecruitInternalFacade}만 부른다.
 */
@RestController
@RequestMapping("/api/v1/internal/appearances/recruits")
@RequiredArgsConstructor
public class ApiV1AppearanceRecruitInternalController implements ApiV1AppearanceRecruitInternalSpec {

    private final AppearanceRecruitInternalFacade appearanceRecruitInternalFacade;

    @Override
    @GetMapping("/{recruitId}")
    public RsData<AppearanceRecruitInternalResDto> getRecruit(@PathVariable("recruitId") Long recruitId) {
        return RsData.ok("출연 모집글을 조회했습니다.", appearanceRecruitInternalFacade.getRecruit(recruitId));
    }

    @Override
    @GetMapping("/{recruitId}/applicants")
    public RsData<AppearanceRecruitApplicantIdsResDto> getApplicantIds(@PathVariable("recruitId") Long recruitId,
            @RequestParam(name = "afterUserId", required = false) Long afterUserId,
            @RequestParam(name = "size", required = false) Integer size) {
        return RsData.ok("출연 모집글 지원자 목록을 조회했습니다.",
                appearanceRecruitInternalFacade.findApplicantIds(recruitId, afterUserId, size));
    }

    @Override
    @GetMapping("/{recruitId}/applicants/exists")
    public RsData<AppearanceRecruitAppliedUserIdsResDto> getAppliedUserIds(@PathVariable("recruitId") Long recruitId,
            @RequestParam(name = "userIds", required = false) List<Long> userIds) {
        return RsData.ok("출연 모집글 지원 이력을 확인했습니다.",
                appearanceRecruitInternalFacade.findAppliedUserIds(recruitId, userIds));
    }
}
