package com.hapbang.auxiliary.notification.app;

import java.util.Optional;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicketResponse;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;

/**
 * 알림 웹소켓 연결 티켓의 진입점(ND-028). 발급은 REST 컨트롤러가, 사용은 웹소켓 핸드셰이크가 부른다.
 * <ul>
 *     <li>발급: 알림을 받는 역할만(ND-052와 같다, 관리자 403).</li>
 *     <li>사용: 핸드셰이크 요청에는 Gateway 사용자 헤더가 없다(브라우저가 Authorization을 싣지 못한다, ND-060). 티켓 자체가 인증이라
 *     {@code @PreAuthorize}를 붙이지 않는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class NotificationConnectionFacade {

    private final NotificationConnectionTicketService ticketService;

    @PreAuthorize(NotificationRoles.RECEIVER)
    public ConnectionTicketResponse issueTicket(Long userId, Role role, String tokenExpiresAtHeader) {
        return ticketService.issue(userId, role, tokenExpiresAtHeader);
    }

    public Optional<ConnectionTicket> consumeTicket(String ticket) {
        return ticketService.consume(ticket);
    }
}
