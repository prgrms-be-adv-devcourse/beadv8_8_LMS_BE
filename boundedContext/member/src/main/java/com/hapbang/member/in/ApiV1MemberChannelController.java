package com.hapbang.member.in;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.exception.BaseErrorCode;
import com.hapbang.global.exception.BusinessException;
import com.hapbang.global.exception.GlobalErrorCode;
import com.hapbang.member.app.ChannelVerificationResult;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.shared.member.domain.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 채널 인증 API (치지직 연동) */
@Slf4j
@RestController
@RequestMapping("/api/v1/members/channels")
@RequiredArgsConstructor
public class ApiV1MemberChannelController implements ApiV1MemberChannelSpec {

    /** 세션에 보관하는 state 이름 (연동 시작 때 저장, 콜백 때 비교 후 삭제) */
    static final String CHZZK_STATE = "CHZZK_STATE";
    /** 연동 결과를 보여 줄 프론트 화면. TODO(프론트): 경로 확정 */
    static final String RESULT_PATH = "/mypage";

    private final MemberFacade memberFacade;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    /** 랜덤 state를 세션에 보관하고 치지직 연동 화면으로 보낸다 */
    @Override
    @GetMapping("/chzzk/connect")
    public ResponseEntity<Void> connectChzzk(HttpServletRequest request) {
        String state = UUID.randomUUID().toString();
        // 유저가 아니면 여기서 403 (Facade의 @PreAuthorize)
        String authorizeUrl = memberFacade.chzzkAuthorizeUrl(state);
        request.getSession().setAttribute(CHZZK_STATE, state);
        return redirect(authorizeUrl);
    }

    /** 브라우저 이동이라 JSON 대신 결과 화면으로 보낸다. 업무 실패(BusinessException)도 화면으로 (code에 오류 코드) */
    @Override
    @GetMapping("/chzzk/callback")
    public ResponseEntity<Void> chzzkCallback(@LoginUserId Long userId,
                                              @RequestParam(name = "code", required = false) String code,
                                              @RequestParam(name = "state", required = false) String state,
                                              HttpServletRequest request, HttpServletResponse response) {
        try {
            verifyState(request, state);
            if (code == null) {
                // 치지직 동의를 취소하는 등 code 없이 돌아온 경우
                throw new MemberException(MemberErrorCode.CHZZK_REQUEST_REJECTED);
            }
            ChannelVerificationResult result = memberFacade.connectChzzkChannel(userId, code, state);
            if (result.roleChanged()) {
                refreshSessionRole(userId, result.role(), request, response);
            }
            return redirect(resultUrl(result.isApproved() ? "approved" : "registered", null));
        } catch (BusinessException e) {
            return redirect(resultUrl("failed", e.getErrorCode()));
        } catch (RuntimeException e) {
            // 외부 장애·예상하지 못한 오류. 브라우저 이동이라 공통 핸들러의 500 JSON 대신 여기서 로그를 남기고 실패 화면으로 보낸다
            log.error("치지직 연동 콜백 실패", e);
            return redirect(resultUrl("failed", GlobalErrorCode.INTERNAL_ERROR));
        }
    }

    /** 내 채널 목록 (상태·반려 사유 포함) */
    @Override
    @GetMapping("/me")
    public List<MyChannelResponse> myChannels(@LoginUserId Long userId) {
        return memberFacade.findChannels(userId).stream().map(MyChannelResponse::from).toList();
    }

    /** 채널 승인 요청. 자동 승인으로 유저 → 인플루언서가 되면 세션 권한도 바꾼다 */
    @Override
    @PostMapping("/{channelId:\\d+}/approval-request")
    public ChannelVerificationResponse requestApproval(@LoginUserId Long userId,
                                                       @PathVariable(name = "channelId") Long channelId,
                                                       HttpServletRequest request, HttpServletResponse response) {
        ChannelVerificationResult result = memberFacade.requestChannelApproval(userId, channelId);
        if (result.roleChanged()) {
            refreshSessionRole(userId, result.role(), request, response);
        }
        return ChannelVerificationResponse.from(result);
    }

    /** 세션에 보관한 state와 같아야 한다 (남의 code를 끼워 넣는 공격 차단). 한 번 쓰면 지운다 */
    private void verifyState(HttpServletRequest request, String state) {
        HttpSession session = request.getSession(false);
        Object savedState = session == null ? null : session.getAttribute(CHZZK_STATE);
        if (session != null) {
            session.removeAttribute(CHZZK_STATE);
        }
        if (state == null || !state.equals(savedState)) {
            throw new MemberException(MemberErrorCode.INVALID_CHZZK_STATE);
        }
    }

    /**
     * 세션 권한을 새 역할로 바꾼다. 세션에는 로그인 때 역할이 들어 있어
     * 바꾸지 않으면 다시 로그인할 때까지 인플루언서 기능에서 403이 난다
     */
    private void refreshSessionRole(Long userId, Role role, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        Map<String, Object> attributes = current.getPrincipal() instanceof OAuth2User user
                ? user.getAttributes() : Map.of();
        String registrationId = current instanceof OAuth2AuthenticationToken token
                ? token.getAuthorizedClientRegistrationId() : "google";

        MemberOAuth2User principal = new MemberOAuth2User(userId, role, attributes);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), registrationId));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    /** 실패 code는 JSON 오류 응답과 같은 기준(BaseErrorCode.getCode()) */
    private String resultUrl(String result, BaseErrorCode errorCode) {
        UriComponentsBuilder url = UriComponentsBuilder.fromPath(RESULT_PATH).queryParam("chzzk", result);
        if (errorCode != null) {
            url.queryParam("code", errorCode.getCode());
        }
        return url.toUriString();
    }

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
