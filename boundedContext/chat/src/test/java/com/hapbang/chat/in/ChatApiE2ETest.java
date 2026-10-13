package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatUserService;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.shared.member.domain.Role;

/**
 * 실제 서버를 띄워 RestTestClient로 채팅 REST API를 검증한다.
 * 운영과 같은 보안 필터(세션 로그인, CSRF)를 거치며, {@code @LoginUserId}에는 로그인 세션의 회원 ID가 들어간다.
 */
@ChatE2ETest
class ChatApiE2ETest {

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserService chatUserService;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserService);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void 로그인하지_않으면_401() {
        client.get().uri("/api/v1/chats")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void CSRF_토큰_없이_변경_요청을_보내면_403() {
        E2ESession host = login(HOST_ID, Role.INFLUENCER);

        client.post().uri("/api/v1/chats")
                .header(HttpHeaders.COOKIE, host.sessionCookie())
                .body(new CreateChatRoomRequest(GUEST_ID))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void 채팅방을_만들면_201_다시_요청하면_같은_방으로_200() {
        E2ESession host = login(HOST_ID, Role.INFLUENCER);
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        ChatRoomResponse created = createRoom(host, GUEST_ID, HttpStatus.CREATED);
        ChatRoomResponse reused = createRoom(guest, HOST_ID, HttpStatus.OK);

        assertThat(reused.chatRoomId()).isEqualTo(created.chatRoomId());
    }

    // 컨트롤러 @PreAuthorize 거절은 global 공통 핸들러가 403 FORBIDDEN으로 응답한다 (서비스 검사 거절은 CHAT_NOT_ALLOWED)
    @Test
    void 일반_유저가_채팅방을_만들면_403_ProblemDetail() {
        ProblemDetail problem = client.post().uri("/api/v1/chats")
                .headers(login(USER_ID, Role.USER)::applyTo)
                .body(new CreateChatRoomRequest(HOST_ID))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "FORBIDDEN");
    }

    @Test
    void 채팅_상대_ID가_없으면_400_ProblemDetail에_항목별_오류를_담는다() {
        ProblemDetail problem = client.post().uri("/api/v1/chats")
                .headers(login(HOST_ID, Role.INFLUENCER)::applyTo)
                .body(new CreateChatRoomRequest(null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
        assertThat(problem.getProperties().get("errors")).asInstanceOf(LIST)
                .containsExactly(Map.of("field", "targetUserId", "message", "채팅 상대 회원 ID는 필수입니다."));
    }

    @Test
    void 메시지_조회_size가_범위를_벗어나면_400() {
        E2ESession host = login(HOST_ID, Role.INFLUENCER);
        Long chatRoomId = createRoom(host, GUEST_ID, HttpStatus.CREATED).chatRoomId();

        ProblemDetail problem = client.get().uri("/api/v1/chats/{id}/messages?size=0", chatRoomId)
                .header(HttpHeaders.COOKIE, host.cookieHeader())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
        assertThat(problem.getProperties().get("errors")).asInstanceOf(LIST)
                .containsExactly(Map.of("field", "size", "message", "size는 1 이상이어야 합니다."));
    }

    @Test
    void 로그인한_회원의_채팅방_목록을_조회한다() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();

        assertThat(myRooms(login(GUEST_ID, Role.VERIFIED_INFLUENCER)))
                .extracting(ChatRoomResponse::chatRoomId).containsExactly(chatRoomId);
        assertThat(myRooms(login(OTHER_INFLUENCER_ID, Role.INFLUENCER))).isEmpty();
    }

    @Test
    void 방을_나가면_204_다시_보내도_204_이후_메시지_조회는_403() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        for (int i = 0; i < 2; i++) {
            client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                    .headers(guest::applyTo)
                    .exchange()
                    .expectStatus().isNoContent();
        }

        ProblemDetail problem = client.get().uri("/api/v1/chats/{id}/messages", chatRoomId)
                .header(HttpHeaders.COOKIE, guest.cookieHeader())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "NOT_CHAT_ROOM_MEMBER");
    }

    @Test
    void 관리자가_아니면_기록_조회는_403() {
        E2ESession host = login(HOST_ID, Role.INFLUENCER);
        Long chatRoomId = createRoom(host, GUEST_ID, HttpStatus.CREATED).chatRoomId();

        client.get().uri("/api/v1/chats/admin/{id}/history", chatRoomId)
                .header(HttpHeaders.COOKIE, host.cookieHeader())
                .exchange()
                .expectStatus().isForbidden();

        ChatRoomHistoryResponse history = client.get().uri("/api/v1/chats/admin/{id}/history", chatRoomId)
                .header(HttpHeaders.COOKIE, login(ADMIN_ID, Role.ADMIN).cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChatRoomHistoryResponse.class)
                .returnResult().getResponseBody();
        assertThat(history).isNotNull();
        assertThat(history.chatRoomId()).isEqualTo(chatRoomId);
    }

    @Test
    void 채팅_회원_역할이_인플루언서여도_세션_역할이_일반_유저면_컨트롤러에서_403() {
        ProblemDetail problem = client.get().uri("/api/v1/chats")
                .header(HttpHeaders.COOKIE, login(HOST_ID, Role.USER).cookieHeader())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "FORBIDDEN");
    }

    @Test
    void 로그인_후_강등되면_세션_역할이_인플루언서여도_서비스에서_403() {
        E2ESession host = login(HOST_ID, Role.INFLUENCER);
        chatUserService.update(activeUser(HOST_ID, "호스트", Role.USER));

        ProblemDetail problem = client.get().uri("/api/v1/chats")
                .header(HttpHeaders.COOKIE, host.cookieHeader())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "CHAT_NOT_ALLOWED");
    }

    @Test
    void 강등된_회원도_채팅방은_나갈_수_있다() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        chatUserService.update(activeUser(HOST_ID, "호스트", Role.USER));

        client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                .headers(login(HOST_ID, Role.USER)::applyTo)
                .exchange()
                .expectStatus().isNoContent();
    }

    private E2ESession login(Long userId, Role role) {
        return E2ESession.login(client, userId, role);
    }

    private ChatRoomResponse createRoom(E2ESession session, Long targetUserId, HttpStatus expectedStatus) {
        ChatRoomResponse room = client.post().uri("/api/v1/chats")
                .headers(session::applyTo)
                .body(new CreateChatRoomRequest(targetUserId))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(ChatRoomResponse.class)
                .returnResult().getResponseBody();
        assertThat(room).isNotNull();
        return room;
    }

    private List<ChatRoomResponse> myRooms(E2ESession session) {
        return client.get().uri("/api/v1/chats")
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<ChatRoomResponse>>() {
                })
                .returnResult().getResponseBody();
    }
}
