package com.hapbang.appearance.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceApplicationFacade;
import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.in.dto.ApplyAppearanceRecruitReqDto;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 지원 REST API. Swagger 명세는 {@link ApiV1AppearanceApplicationSpec}. 로직은 {@link AppearanceApplicationFacade}만 부른다.
 * <ul>
 *     <li>모집글에 딸린 동작(지원, 지원자 목록): {@code /api/v1/appearances/recruits/{recruitId}/applications}</li>
 *     <li>지원 하나에 대한 동작(취소·수락·거절)과 내 지원 목록: {@code /api/v1/appearances/applications}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/appearances")
@RequiredArgsConstructor
public class ApiV1AppearanceApplicationController implements ApiV1AppearanceApplicationSpec {

    private final AppearanceApplicationFacade appearanceApplicationFacade;

    @Override
    @PostMapping("/recruits/{recruitId}/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<AppearanceApplicationResDto> apply(@LoginUserId Long userId,
                                                     @PathVariable("recruitId") Long recruitId,
                                                     @RequestBody(required = false) ApplyAppearanceRecruitReqDto request) {
        String message = request == null ? null : request.message();
        AppearanceApplicationResDto applied = appearanceApplicationFacade.apply(userId, recruitId, message);
        return RsData.created("출연 모집에 지원했습니다.", applied);
    }

    @Override
    @GetMapping("/recruits/{recruitId}/applications")
    public RsData<Page<AppearanceApplicationResDto>> getApplicants(@LoginUserId Long userId,
            @PathVariable("recruitId") Long recruitId,
            @RequestParam(name = "status", required = false) List<AppearanceApplicationStatus> statuses,
            Pageable pageable) {
        Page<AppearanceApplicationResDto> applicants = appearanceApplicationFacade.getApplicants(userId, recruitId,
                statuses, pageable);
        return RsData.ok("지원자 목록을 조회했습니다.", applicants);
    }

    @Override
    @GetMapping("/applications/mine")
    public RsData<Page<AppearanceApplicationResDto>> getMine(@LoginUserId Long userId,
            @RequestParam(name = "status", required = false) List<AppearanceApplicationStatus> statuses,
            Pageable pageable) {
        Page<AppearanceApplicationResDto> mine = appearanceApplicationFacade.getMine(userId, statuses, pageable);
        return RsData.ok("내 지원 목록을 조회했습니다.", mine);
    }

    @Override
    @PostMapping("/applications/{applicationId}/withdraw")
    public RsData<AppearanceApplicationResDto> withdraw(@LoginUserId Long userId,
                                                        @PathVariable("applicationId") Long applicationId) {
        AppearanceApplicationResDto withdrawn = appearanceApplicationFacade.withdraw(userId, applicationId);
        return RsData.ok("지원을 취소했습니다.", withdrawn);
    }

    @Override
    @PostMapping("/applications/{applicationId}/accept")
    public RsData<AppearanceApplicationResDto> accept(@LoginUserId Long userId,
                                                      @PathVariable("applicationId") Long applicationId) {
        AppearanceApplicationResDto accepted = appearanceApplicationFacade.accept(userId, applicationId);
        return RsData.ok("지원을 수락했습니다.", accepted);
    }

    @Override
    @PostMapping("/applications/{applicationId}/reject")
    public RsData<AppearanceApplicationResDto> reject(@LoginUserId Long userId,
                                                      @PathVariable("applicationId") Long applicationId) {
        AppearanceApplicationResDto rejected = appearanceApplicationFacade.reject(userId, applicationId);
        return RsData.ok("지원을 거절했습니다.", rejected);
    }
}
