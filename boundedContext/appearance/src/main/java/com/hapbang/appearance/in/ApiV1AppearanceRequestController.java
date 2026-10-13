package com.hapbang.appearance.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceRequestFacade;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestSummaryResDto;
import com.hapbang.appearance.in.dto.CreateAppearanceRequestReqDto;
import com.hapbang.appearance.in.dto.UpdateAppearanceRequestReqDto;
import com.hapbang.global.auth.LoginUserId;

import lombok.RequiredArgsConstructor;

/**
 * 출연 요청서 REST API. Swagger 명세는 {@link ApiV1AppearanceRequestSpec}. 로직은 {@link AppearanceRequestFacade}만 부른다.
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
    public ResponseEntity<AppearanceRequestResDto> create(@LoginUserId Long userId,
                                                          @RequestBody CreateAppearanceRequestReqDto request) {
        AppearanceRequestResDto response = appearanceRequestFacade.create(userId, request.guestId(),
                request.toContent());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Override
    @GetMapping("/sent")
    public Page<AppearanceRequestSummaryResDto> getSent(@LoginUserId Long userId, Pageable pageable) {
        return appearanceRequestFacade.getSent(userId, pageable);
    }

    @Override
    @GetMapping("/received")
    public Page<AppearanceRequestSummaryResDto> getReceived(@LoginUserId Long userId, Pageable pageable) {
        return appearanceRequestFacade.getReceived(userId, pageable);
    }

    @Override
    @GetMapping("/{requestId}")
    public AppearanceRequestResDto getDetail(@LoginUserId Long userId, @PathVariable("requestId") Long requestId) {
        return appearanceRequestFacade.getDetail(userId, requestId);
    }

    @Override
    @PatchMapping("/{requestId}")
    public AppearanceRequestResDto updateBasicInfo(@LoginUserId Long userId,
                                                   @PathVariable("requestId") Long requestId,
                                                   @RequestBody UpdateAppearanceRequestReqDto request) {
        return appearanceRequestFacade.updateBasicInfo(userId, requestId, request.title(), request.content(),
                request.hostChannelUrl());
    }
}
