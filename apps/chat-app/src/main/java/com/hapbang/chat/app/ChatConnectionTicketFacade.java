package com.hapbang.chat.app;

import java.util.Optional;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.chat.app.dto.ChatConnectionTicketResponse;
import com.hapbang.chat.app.dto.ChatReauthResult;
import com.hapbang.chat.app.dto.ChatReauthResult.Outcome;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 웹소켓 연결 티켓(일회용)의 진입점(설계 문서 6장). 발급은 REST 컨트롤러, 사용은 웹소켓 핸드셰이크 인터셉터와 재인증 STOMP
 * 컨트롤러가 부른다.
 * <ul>
 *     <li>발급: 채팅 역할만 받을 수 있다({@code @PreAuthorize}, 다른 채팅 API와 같다).</li>
 *     <li>사용·재인증: 핸드셰이크와 STOMP 처리 스레드에는 Spring Security 인증 정보가 없으므로 {@code @PreAuthorize}를 붙이지 않는다.
 *     티켓 자체가 인증이다.</li>
 * </ul>
 * 티켓 생성·만료 계산·발급 상한은 {@link ChatConnectionTicketService} 구현이, 연결 만료 교체는 {@link LocalChatConnections}가 맡는다.
 * 이 Facade는 웹소켓·Redis 타입을 모른다. 티켓 원문은 로그에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatConnectionTicketFacade {

    private final ChatConnectionTicketService chatConnectionTicketService;
    private final LocalChatConnections localChatConnections;

    @PreAuthorize(ChatRoles.CHAT)
    public ChatConnectionTicketResponse issueTicket(Long userId, Role role, String tokenExpiresAtHeader) {
        return chatConnectionTicketService.issue(userId, role, tokenExpiresAtHeader);
    }

    public Optional<ChatConnectionTicket> consumeTicket(String ticket) {
        return chatConnectionTicketService.consume(ticket);
    }

    /**
     * 열린 연결을 새 티켓으로 재인증한다(설계 문서 6장 "재인증"). 티켓은 성공·실패와 관계없이 쓴 것으로 사라진다. 실패해도 연결을 닫지
     * 않고 기존 만료에 닫히게 둔다.
     *
     * @param sessionId 재인증 프레임을 보낸 연결(STOMP 세션 ID)
     * @param ticket    재인증 프레임의 {@code ticket} 헤더 값. 없으면 null
     */
    public ChatReauthResult reauthenticate(String sessionId, String ticket) {
        ChatConnectionTicket consumed = chatConnectionTicketService.consume(ticket).orElse(null);
        if (consumed == null) {
            return ChatReauthResult.failed(Outcome.INVALID_TICKET, localChatConnections.authenticatedUntil(sessionId));
        }
        ChatReauthResult result = localChatConnections.reauthenticate(sessionId, consumed.userId(),
                consumed.tokenExpiresAt());
        if (result.outcome() == Outcome.MEMBER_MISMATCH) {
            log.warn("다른 회원의 연결 티켓으로 재인증을 시도해 거절함 sessionId={}, ticketUserId={}", sessionId,
                    consumed.userId());
        }
        return result;
    }
}
