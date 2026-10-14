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

import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestSummaryResDto;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.in.dto.AcceptAppearanceRequestReqDto;
import com.hapbang.appearance.in.dto.CreateAppearanceRequestReqDto;
import com.hapbang.appearance.in.dto.RejectAppearanceRequestReqDto;
import com.hapbang.appearance.in.dto.UpdateAppearanceRequestReqDto;
import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 REST API. Swagger 명세는 {@link ApiV1AppearanceRequestSpec}. 로직은 {@link AppearanceRequestFacade}만 부른다.
 * 성공 응답은 {@link RsData}로 감싸고, 200이 아니면 {@code @ResponseStatus}로 HTTP 상태를 본문 {@code statusCode}와 같게 맞춘다.
 * <p>
 * 권한 확인 순서
 * <ol>
 *     <li>로그인: global SecurityConfig가 로그인하지 않은 요청을 401로 막는다.</li>
 *     <li>세션 역할: Facade의 {@code @PreAuthorize}가 인플루언서 이상인지 확인한다 (관리자 제외, AGENTS.md 6장).</li>
 *     <li>출연 회원 자격: UseCase가 복제된 역할·상태로 다시 확인한다. 세션 역할은 로그인 시점 값이라,
 *     로그인 이후 정지·강등된 경우는 여기서 막힌다.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/appearances/requests")
@RequiredArgsConstructor
public class ApiV1AppearanceRequestController implements ApiV1AppearanceRequestSpec {

    private final AppearanceRequestFacade appearanceRequestFacade;

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<AppearanceRequestResDto> create(@LoginUserId Long userId,
                                                  @RequestBody CreateAppearanceRequestReqDto request) {
        AppearanceRequestResDto response = appearanceRequestFacade.create(userId, request.guestId(),
                request.toContent());
        return RsData.created("출연 요청을 보냈습니다.", response);
    }

    @Override
    @GetMapping("/sent")
    public RsData<Page<AppearanceRequestSummaryResDto>> getSent(@LoginUserId Long userId,
            @RequestParam(name = "status", required = false) List<AppearanceRequestStatus> statuses,
            Pageable pageable) {
        Page<AppearanceRequestSummaryResDto> sent = appearanceRequestFacade.getSent(userId, statuses, pageable);
        return RsData.ok("보낸 출연 요청 목록을 조회했습니다.", sent);
    }

    @Override
    @GetMapping("/received")
    public RsData<Page<AppearanceRequestSummaryResDto>> getReceived(@LoginUserId Long userId,
            @RequestParam(name = "status", required = false) List<AppearanceRequestStatus> statuses,
            Pageable pageable) {
        Page<AppearanceRequestSummaryResDto> received = appearanceRequestFacade.getReceived(userId, statuses,
                pageable);
        return RsData.ok("받은 출연 요청 목록을 조회했습니다.", received);
    }

    @Override
    @GetMapping("/{requestId}")
    public RsData<AppearanceRequestResDto> getDetail(@LoginUserId Long userId,
                                                     @PathVariable("requestId") Long requestId) {
        AppearanceRequestResDto detail = appearanceRequestFacade.getDetail(userId, requestId);
        return RsData.ok("출연 요청서를 조회했습니다.", detail);
    }

    @Override
    @PatchMapping("/{requestId}")
    public RsData<AppearanceRequestResDto> updateBasicInfo(@LoginUserId Long userId,
                                                           @PathVariable("requestId") Long requestId,
                                                           @RequestBody UpdateAppearanceRequestReqDto request) {
        AppearanceRequestResDto updated = appearanceRequestFacade.updateBasicInfo(userId, requestId,
                request.title(), request.content(), request.hostChannelUrl());
        return RsData.ok("출연 요청서를 수정했습니다.", updated);
    }

    @Override
    @PostMapping("/{requestId}/accept")
    public RsData<AppearanceRequestResDto> accept(@LoginUserId Long userId,
                                                  @PathVariable("requestId") Long requestId,
                                                  @RequestBody AcceptAppearanceRequestReqDto request) {
        AppearanceRequestResDto accepted = appearanceRequestFacade.accept(userId, requestId, request.version());
        return RsData.ok("출연 요청을 수락했습니다.", accepted);
    }

    @Override
    @PostMapping("/{requestId}/reject")
    public RsData<AppearanceRequestResDto> reject(@LoginUserId Long userId,
                                                  @PathVariable("requestId") Long requestId,
                                                  @RequestBody RejectAppearanceRequestReqDto request) {
        AppearanceRequestResDto rejected = appearanceRequestFacade.reject(userId, requestId, request.reason());
        return RsData.ok("출연 요청을 거절했습니다.", rejected);
    }

    @Override
    @PostMapping("/{requestId}/cancel")
    public RsData<AppearanceRequestResDto> cancel(@LoginUserId Long userId,
                                                  @PathVariable("requestId") Long requestId) {
        AppearanceRequestResDto canceled = appearanceRequestFacade.cancel(userId, requestId);
        return RsData.ok("출연 요청을 취소했습니다.", canceled);
    }
}
