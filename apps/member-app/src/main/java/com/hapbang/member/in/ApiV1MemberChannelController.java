package com.hapbang.member.in;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.hapbang.member.global.auth.LoginUserId;
import com.hapbang.member.global.exception.BaseErrorCode;
import com.hapbang.member.global.exception.BusinessException;
import com.hapbang.member.global.exception.GlobalErrorCode;
import com.hapbang.member.global.response.RsData;
import com.hapbang.member.app.ChannelVerificationResult;
import com.hapbang.member.app.ChzzkAuthorization;
import com.hapbang.member.app.MemberFacade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 채널 인증 API (치지직 연동). 역할이 유저 → 인플루언서로 바뀌면 새 역할은 회원의 다음 access token 재발급에 반영된다
 * (토큰은 고칠 수 없다, #370). 프론트는 승인 결과를 받으면 재발급 API를 부른다
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/members/channels")
@RequiredArgsConstructor
public class ApiV1MemberChannelController implements ApiV1MemberChannelSpec {

    /** 연동을 시작한 브라우저 ID를 담는 쿠키 (#370). 콜백에 붙어야 해서 SameSite=Lax, 치지직 연동 경로에서만 */
    static final String CHZZK_AUTH_COOKIE = "CHZZK_AUTH";
    private static final String CHZZK_COOKIE_PATH = "/api/v1/members/channels/chzzk";
    /** 연동 결과를 보여 줄 프론트 화면. TODO(프론트): 경로 확정 */
    static final String RESULT_PATH = "/mypage";

    private final MemberFacade memberFacade;

    /**
     * 연동 시작 (#370): 로그인한 API 요청으로 부른다. 연동하는 회원과 state를 맡기고, 치지직 화면 주소를 본문으로 준다.
     * 프론트는 받은 주소로 이동한다 (브라우저 이동에는 access token을 실을 수 없어서 시작을 API로 나눴다)
     */
    @Override
    @PostMapping("/chzzk/authorize")
    public ResponseEntity<RsData<ChzzkAuthorizeResponse>> authorizeChzzk(@LoginUserId Long userId) {
        // 유저가 아니면 여기서 403 (Facade의 @PreAuthorize)
        ChzzkAuthorization authorization = memberFacade.startChzzkConnect(userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        chzzkAuthCookie(authorization.browserId(), authorization.ttl()).toString())
                .body(RsData.ok("치지직 연동 주소를 만들었습니다.",
                        new ChzzkAuthorizeResponse(authorization.authorizeUrl())));
    }

    /**
     * 브라우저 이동이라 로그인 정보 없이 들어온다. 연동을 시작한 브라우저 쿠키로 맡겨 둔 회원을 찾아 연동한다 (쿠키가 없으면 실패).
     * JSON 대신 결과 화면으로 보내고, 업무 실패(BusinessException)도 화면으로 보낸다 (code에 오류 코드)
     */
    @Override
    @GetMapping("/chzzk/callback")
    public ResponseEntity<Void> chzzkCallback(
            @CookieValue(name = CHZZK_AUTH_COOKIE, required = false) String browserId,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state) {
        try {
            ChannelVerificationResult result = memberFacade.completeChzzkConnect(browserId, code, state).result();
            return callbackRedirect(resultUrl(result.isApproved() ? "approved" : "registered", null));
        } catch (BusinessException e) {
            return callbackRedirect(resultUrl("failed", e.getErrorCode()));
        } catch (RuntimeException e) {
            // 외부 장애·예상하지 못한 오류. 브라우저 이동이라 공통 핸들러의 500 JSON 대신 여기서 로그를 남기고 실패 화면으로 보낸다
            log.error("치지직 연동 콜백 실패", e);
            return callbackRedirect(resultUrl("failed", GlobalErrorCode.INTERNAL_ERROR));
        }
    }

    /** 내 채널 목록 (상태·반려 사유 포함) */
    @Override
    @GetMapping("/me")
    public RsData<List<MyChannelResponse>> myChannels(@LoginUserId Long userId) {
        return RsData.ok("내 채널 목록을 조회했습니다.",
                memberFacade.findChannels(userId).stream().map(MyChannelResponse::from).toList());
    }

    /** 채널 승인 요청. 자동 승인으로 유저 → 인플루언서가 되면 프론트가 access token을 재발급받아 새 역할을 쓴다 */
    @Override
    @PostMapping("/{channelId:\\d+}/approval-request")
    public RsData<ChannelVerificationResponse> requestApproval(@LoginUserId Long userId,
                                                       @PathVariable(name = "channelId") Long channelId) {
        ChannelVerificationResult result = memberFacade.requestChannelApproval(userId, channelId);
        return RsData.ok(result.isApproved() ? "채널이 승인되었습니다." : "채널 승인을 요청했습니다.",
                ChannelVerificationResponse.from(result));
    }

    /** 실패 code는 JSON 오류 응답과 같은 기준(BaseErrorCode.getCode()) */
    private String resultUrl(String result, BaseErrorCode errorCode) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath(RESULT_PATH).queryParam("chzzk", result);
        if (errorCode != null) {
            url.queryParam("code", errorCode.getCode());
        }
        return url.toUriString();
    }

    /** 콜백 결과 화면으로 보내면서, 다 쓴 연동 쿠키를 지운다 */
    private ResponseEntity<Void> callbackRedirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url))
                .header(HttpHeaders.SET_COOKIE, chzzkAuthCookie("", Duration.ZERO).toString())
                .build();
    }

    private static ResponseCookie chzzkAuthCookie(String browserId, Duration maxAge) {
        return ResponseCookie.from(CHZZK_AUTH_COOKIE, browserId)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(CHZZK_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
