package com.hapbang.appearance.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceRecruitFacade;
import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitSummaryResDto;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.in.dto.CreateAppearanceRecruitReqDto;
import com.hapbang.appearance.in.dto.UpdateAppearanceRecruitReqDto;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 출연 모집글 REST API. Swagger 명세는 {@link ApiV1AppearanceRecruitSpec}. 로직은 {@link AppearanceRecruitFacade}만 부른다.
 * <p>
 * 권한 확인 순서
 * <ol>
 *     <li>로그인: global SecurityConfig가 로그인하지 않은 요청을 401로 막는다 (비회원 공개 목록은 팀 합의 후).</li>
 *     <li>세션 역할: 올리기·수정·내 목록은 Facade의 {@code @PreAuthorize}가 인플루언서 이상인지 확인한다.</li>
 *     <li>출연 회원 자격: 올리기·수정·내 목록은 UseCase가 복제된 역할·상태로 다시 확인한다.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/appearances/recruits")
@RequiredArgsConstructor
public class ApiV1AppearanceRecruitController implements ApiV1AppearanceRecruitSpec {

    private final AppearanceRecruitFacade appearanceRecruitFacade;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<AppearanceRecruitResDto> create(@LoginUserId Long userId,
                                                  @RequestBody CreateAppearanceRecruitReqDto request) {
        AppearanceRecruitResDto response = appearanceRecruitFacade.create(userId, request.toContent());
        return RsData.created("출연 모집글을 올렸습니다.", response);
    }

    @Override
    @GetMapping
    public RsData<Page<AppearanceRecruitSummaryResDto>> getOpen(Pageable pageable) {
        Page<AppearanceRecruitSummaryResDto> open = appearanceRecruitFacade.getOpen(pageable);
        return RsData.ok("모집 중인 출연 모집글 목록을 조회했습니다.", open);
    }

    @Override
    @GetMapping("/mine")
    public RsData<Page<AppearanceRecruitSummaryResDto>> getMine(@LoginUserId Long userId,
            @RequestParam(name = "status", required = false) List<AppearanceRecruitStatus> statuses,
            Pageable pageable) {
        Page<AppearanceRecruitSummaryResDto> mine = appearanceRecruitFacade.getMine(userId, statuses, pageable);
        return RsData.ok("내 출연 모집글 목록을 조회했습니다.", mine);
    }

    @Override
    @GetMapping("/{recruitId}")
    public RsData<AppearanceRecruitResDto> getDetail(@LoginUserId Long userId,
                                                     @PathVariable("recruitId") Long recruitId) {
        AppearanceRecruitResDto detail = appearanceRecruitFacade.getDetail(userId, recruitId);
        return RsData.ok("출연 모집글을 조회했습니다.", detail);
    }

    @Override
    @PatchMapping("/{recruitId}")
    public RsData<AppearanceRecruitResDto> updateBasicInfo(@LoginUserId Long userId,
                                                           @PathVariable("recruitId") Long recruitId,
                                                           @RequestBody UpdateAppearanceRecruitReqDto request) {
        AppearanceRecruitResDto updated = appearanceRecruitFacade.updateBasicInfo(userId, recruitId,
                request.title(), request.content(), request.hostChannelUrl());
        return RsData.ok("출연 모집글을 수정했습니다.", updated);
    }

    @Override
    @PostMapping("/{recruitId}/close")
    public RsData<AppearanceRecruitResDto> closeEarly(@LoginUserId Long userId,
                                                      @PathVariable("recruitId") Long recruitId) {
        AppearanceRecruitResDto closed = appearanceRecruitFacade.closeEarly(userId, recruitId);
        return RsData.ok("출연 모집을 마감했습니다.", closed);
    }
}
