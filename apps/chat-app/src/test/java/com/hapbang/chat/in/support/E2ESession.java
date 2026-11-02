package com.hapbang.chat.in.support;

import static org.assertj.core.api.Assertions.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatConnectionTicketStore;
import com.hapbang.chat.app.dto.ChatConnectionTicket;
import com.hapbang.chat.app.dto.ChatConnectionTicketResponse;
import com.hapbang.chat.global.auth.GatewayUserHeaderFilter;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.shared.member.domain.Role;

/**
 * 실제 서버에 요청하는 로그인 회원. 서버를 부르지 않고 만든다(로그인·토큰 발급은 회원 앱의 책임이다). 프론트·Gateway와 같은
 * 방식으로 요청한다(설계 문서 6장).
 * <ul>
 *     <li>REST: Gateway가 access token을 검증한 뒤 넘기는 사용자 헤더({@code X-User-Id}·{@code X-User-Role})를 싣는다.</li>
 *     <li>웹소켓: REST로 일회용 연결 티켓을 받아 {@code /api/v1/chats/ws?ticket=...}로 연결한다.</li>
 * </ul>
 */
public record E2ESession(Long userId, Role role) {

    public static final String WS_PATH = "/api/v1/chats/ws";
    public static final String TICKET_PATH = "/api/v1/chats/ws-tickets";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static E2ESession of(Long userId, Role role) {
        return new E2ESession(userId, role);
    }

    /** REST 요청에 Gateway 사용자 헤더를 붙인다 */
    public void applyTo(HttpHeaders headers) {
        headers.set(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId));
        headers.set(GatewayUserHeaderFilter.USER_ROLE, role.name());
    }

    /** REST로 연결 티켓을 받는다(채팅 역할만 받을 수 있다) */
    public String issueTicket(RestTestClient client) {
        RsData<ChatConnectionTicketResponse> response = client.post().uri(TICKET_PATH)
                .headers(this::applyTo)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatConnectionTicketResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response.data().ticket();
    }

    /** 연결 티켓을 받아 웹소켓 연결 주소를 만든다 */
    public String connectUrl(RestTestClient client, int port) {
        return wsUrl(port, issueTicket(client));
    }

    public static String wsUrl(int port, String ticket) {
        return "ws://localhost:" + port + WS_PATH + "?ticket=" + ticket;
    }

    /**
     * 발급 API를 거치지 않고 저장소에 티켓을 넣는다. 발급 API가 받지 않는 역할(일반 회원)이나 만료를 시험할 때 쓴다.
     */
    public static String saveTicket(ChatConnectionTicketStore store, Long userId, Role role, Duration ttl,
            Instant tokenExpiresAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.save(ticket, new ChatConnectionTicket(userId, role, Instant.now(), tokenExpiresAt), ttl);
        return ticket;
    }
}
