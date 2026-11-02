package com.hapbang.appearance.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceFacade;
import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.app.dto.AppearanceSummaryResDto;
import com.hapbang.appearance.domain.AppearanceRole;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.in.dto.AbsentAppearanceReqDto;
import com.hapbang.appearance.in.dto.HostCancelAppearanceReqDto;
import com.hapbang.appearance.in.dto.RejectAppearanceCancelReqDto;
import com.hapbang.appearance.in.dto.RequestAppearanceCancelReqDto;
import com.hapbang.appearance.global.auth.LoginUserId;
import com.hapbang.appearance.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 출연(성립 건) REST API. Swagger 명세는 {@link ApiV1AppearanceSpec}. 로직은 {@link AppearanceFacade}만 부른다.
 * <p>
 * 출연 ID는 숫자만 받는다. 같은 {@code /api/v1/appearances} 아래의 {@code /requests}·{@code /recruits}·{@code /applications}
 * 경로와 섞이지 않게 하기 위해서다.
 */
@RestController
@RequestMapping("/api/v1/appearances")
@RequiredArgsConstructor
public class ApiV1AppearanceController implements ApiV1AppearanceSpec {

    private final AppearanceFacade appearanceFacade;

    @Override
    @GetMapping
    public RsData<Page<AppearanceSummaryResDto>> getMine(@LoginUserId Long userId,
            @RequestParam("role") AppearanceRole role,
            @RequestParam(name = "status", required = false) List<AppearanceStatus> statuses,
            Pageable pageable) {
        Page<AppearanceSummaryResDto> mine = appearanceFacade.getMine(userId, role, statuses, pageable);
        return RsData.ok("내 출연 목록을 조회했습니다.", mine);
    }

    @Override
    @GetMapping("/{appearanceId:\\d+}")
    public RsData<AppearanceResDto> getDetail(@LoginUserId Long userId,
                                              @PathVariable("appearanceId") Long appearanceId) {
        AppearanceResDto detail = appearanceFacade.getDetail(userId, appearanceId);
        return RsData.ok("출연을 조회했습니다.", detail);
    }

    @Override
    @PostMapping("/{appearanceId:\\d+}/cancel-requests")
    public RsData<AppearanceResDto> requestCancel(@LoginUserId Long userId,
                                                  @PathVariable("appearanceId") Long appearanceId,
                                                  @RequestBody RequestAppearanceCancelReqDto request) {
        AppearanceResDto requested = appearanceFacade.requestCancel(userId, appearanceId, request.reason());
        return RsData.ok("출연 취소를 요청했습니다.", requested);
    }

    @Override
    @PostMapping("/{appearanceId:\\d+}/cancel-requests/accept")
    public RsData<AppearanceResDto> acceptCancel(@LoginUserId Long userId,
                                                 @PathVariable("appearanceId") Long appearanceId) {
        AppearanceResDto accepted = appearanceFacade.acceptCancel(userId, appearanceId);
        return RsData.ok("출연 취소 요청을 수락했습니다.", accepted);
    }

    @Override
    @PostMapping("/{appearanceId:\\d+}/cancel-requests/reject")
    public RsData<AppearanceResDto> rejectCancel(@LoginUserId Long userId,
                                                 @PathVariable("appearanceId") Long appearanceId,
                                                 @RequestBody(required = false) RejectAppearanceCancelReqDto request) {
        String reason = request == null ? null : request.reason();
        AppearanceResDto rejected = appearanceFacade.rejectCancel(userId, appearanceId, reason);
        return RsData.ok("출연 취소 요청을 거절했습니다.", rejected);
    }

    @Override
    @PostMapping("/{appearanceId:\\d+}/host-cancel")
    public RsData<AppearanceResDto> hostCancel(@LoginUserId Long userId,
                                               @PathVariable("appearanceId") Long appearanceId,
                                               @RequestBody(required = false) HostCancelAppearanceReqDto request) {
        String reason = request == null ? null : request.reason();
        boolean confirmed = request != null && request.confirmed();
        AppearanceResDto canceled = appearanceFacade.hostCancel(userId, appearanceId, confirmed, reason);
        return RsData.ok("출연을 취소했습니다.", canceled);
    }

    @Override
    @PostMapping("/{appearanceId:\\d+}/confirm")
    public RsData<AppearanceResDto> confirm(@LoginUserId Long userId,
                                            @PathVariable("appearanceId") Long appearanceId) {
        AppearanceResDto confirmed = appearanceFacade.confirm(userId, appearanceId);
        return RsData.ok("출연을 확인했습니다.", confirmed);
    }

    @Override
    @PostMapping("/{appearanceId:\\d+}/absent")
    public RsData<AppearanceResDto> markAbsent(@LoginUserId Long userId,
                                               @PathVariable("appearanceId") Long appearanceId,
                                               @RequestBody(required = false) AbsentAppearanceReqDto request) {
        String reason = request == null ? null : request.reason();
        AppearanceResDto absent = appearanceFacade.markAbsent(userId, appearanceId, reason);
        return RsData.ok("불참으로 처리했습니다.", absent);
    }
}
