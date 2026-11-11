package com.hapbang.auxiliary.notification.in;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;
import com.hapbang.auxiliary.notification.app.NotificationConnectionFacade;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicketResponse;
import com.hapbang.shared.member.domain.Role;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 알림 웹소켓 연결 티켓(`POST /api/v1/notifications/ws-tickets`, ND-026·ND-028·ND-056). 받은 티켓으로
 * {@code /api/v1/notifications/ws?ticket=...}에 연결하고 {@code /user/queue/notifications}를 구독한다.
 */
@Tag(name = "Notification", description = "알림센터 (유저·인플루언서·인증된 인플루언서)")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class ApiV1NotificationConnectionController {

    /**
     * access token의 {@code exp}(epoch 초). N4에서 Gateway가 넣도록 설정하기 전에는 믿을 수 있는 값이 아니어서 연결 만료를 앞당기는 데만
     * 쓴다(ND-056). auxiliary global 필터는 이 헤더를 읽지 않는다.
     */
    static final String USER_TOKEN_EXP = "X-User-Token-Exp";

    private final NotificationConnectionFacade facade;

    @Operation(summary = "웹소켓 연결 티켓", description = "일회용 티켓(유효 30초)을 준다. 회원마다 60초에 10번까지다(넘으면 429). "
            + "연결은 connectionExpiresAt(최대 발급 + 15분)에 서버가 닫고(종료 코드 4401), 그때 새 티켓으로 다시 연결한다.")
    @PostMapping("/ws-tickets")
    public RsData<ConnectionTicketResponse> issueTicket(@LoginUserId Long userId, Authentication authentication,
            @RequestHeader(name = USER_TOKEN_EXP, required = false) String tokenExpiresAt) {
        return RsData.ok("알림 연결 티켓을 발급했습니다.", facade.issueTicket(userId, roleOf(authentication), tokenExpiresAt));
    }

    /** 로그인 정보의 역할. Gateway 사용자 헤더 인증은 권한이 {@code ROLE_{역할}} 하나다 */
    private static Role roleOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> Role.valueOf(authority.substring("ROLE_".length())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("로그인 정보에 역할이 없습니다."));
    }
}
