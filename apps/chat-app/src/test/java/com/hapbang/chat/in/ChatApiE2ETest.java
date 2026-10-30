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

import com.hapbang.chat.app.ChatMessageUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.app.dto.ChatRoomHistoryResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.ReadChatRoomRequest;
import com.hapbang.chat.in.dto.ReceivedChatRoomRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.out.ChatRoomMemberRepository;
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
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void Swagger_명세에_알림_값이_종류별_필드와_record로_나온다() {
        Map<String, Object> apiDocs = client.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .returnResult().getResponseBody();

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) apiDocs.get("components"))
                .get("schemas");
        for (String response : List.of("ChatMessageResponse", "ChatMessageHistoryResponse")) {
            Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>) schemas.get(response))
                    .get("properties");
            assertThat(properties).doesNotContainKey("notificationData");
            assertThat(properties.get("appearanceRequestSent")).asString()
                    .contains("#/components/schemas/AppearanceRequestSentData");
            assertThat(properties.get("appearanceApplicationSubmitted")).asString()
                    .contains("#/components/schemas/AppearanceApplicationSubmittedData");
        }
        assertThat((Map<String, Object>) ((Map<String, Object>) schemas.get("AppearanceRequestSentData"))
                .get("properties")).containsOnlyKeys("v", "hostNickname", "title", "fee", "scheduledAt",
                "acceptDeadline");
        assertThat((Map<String, Object>) ((Map<String, Object>) schemas.get("AppearanceApplicationSubmittedData"))
                .get("properties")).containsOnlyKeys("v", "guestNickname", "recruitTitle");
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

    // Facade @PreAuthorize 거절은 global 공통 핸들러가 403 FORBIDDEN으로 응답한다 (UseCase 검사 거절은 CHAT_NOT_ALLOWED)
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
    void 읽음_처리하면_204_순번이_없으면_400() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        client.put().uri("/api/v1/chats/{id}/read", chatRoomId)
                .headers(guest::applyTo)
                .body(new ReadChatRoomRequest(0L, null))
                .exchange()
                .expectStatus().isNoContent();

        ProblemDetail problem = client.put().uri("/api/v1/chats/{id}/read", chatRoomId)
                .headers(guest::applyTo)
                .body(new ReadChatRoomRequest(null, null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
        assertThat(myRooms(guest)).singleElement().extracting(ChatRoomResponse::unreadCount).isEqualTo(0L);
    }

    @Test
    void 읽음_처리에_현재_세대를_보내면_204_옛_세대는_409_ProblemDetail_1_미만은_400() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", null);
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        ProblemDetail stale = client.put().uri("/api/v1/chats/{id}/read", chatRoomId)
                .headers(guest::applyTo)
                .body(new ReadChatRoomRequest(2L, 2))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();
        assertThat(stale).isNotNull();
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(stale.getProperties()).containsEntry("code", "STALE_PARTICIPATION");
        assertThat(stale.getDetail()).isEqualTo("현재 참여 세대의 요청이 아닙니다. 채팅방 목록과 이력을 다시 불러와 주세요.");
        assertThat(myRooms(guest)).singleElement().extracting(ChatRoomResponse::unreadCount).isEqualTo(2L);

        ProblemDetail invalid = client.put().uri("/api/v1/chats/{id}/read", chatRoomId)
                .headers(guest::applyTo)
                .body(new ReadChatRoomRequest(2L, 0))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();
        assertThat(invalid).isNotNull();
        assertThat(invalid.getProperties()).containsEntry("code", "INVALID_REQUEST");

        client.put().uri("/api/v1/chats/{id}/read", chatRoomId)
                .headers(guest::applyTo)
                .body(new ReadChatRoomRequest(2L, 1))
                .exchange()
                .expectStatus().isNoContent();
        assertThat(myRooms(guest)).singleElement().extracting(ChatRoomResponse::unreadCount).isEqualTo(0L);
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
    void 관리자_기록_조회_size가_상한을_넘거나_커서가_틀리면_400() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        E2ESession admin = login(ADMIN_ID, Role.ADMIN);

        client.get().uri("/api/v1/chats/admin/{id}/history?size=101", chatRoomId)
                .header(HttpHeaders.COOKIE, admin.cookieHeader())
                .exchange()
                .expectStatus().isBadRequest();
        ProblemDetail problem = client.get().uri("/api/v1/chats/admin/{id}/history?cursor=bad", chatRoomId)
                .header(HttpHeaders.COOKIE, admin.cookieHeader())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
    }

    @Test
    void 관리자가_아니면_기록_조회는_403() {
        E2ESession host = login(HOST_ID, Role.INFLUENCER);
        Long chatRoomId = createRoom(host, GUEST_ID, HttpStatus.CREATED).chatRoomId();

        client.get().uri("/api/v1/chats/admin/{id}/history", chatRoomId)
                .header(HttpHeaders.COOKIE, host.cookieHeader())
                .exchange()
                .expectStatus().isForbidden();

        RsData<ChatRoomHistoryResponse> history = client.get().uri("/api/v1/chats/admin/{id}/history", chatRoomId)
                .header(HttpHeaders.COOKIE, login(ADMIN_ID, Role.ADMIN).cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatRoomHistoryResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(history).isNotNull();
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(history.data().chatRoomId()).isEqualTo(chatRoomId);
    }

    @Test
    void 채팅_회원_역할이_인플루언서여도_세션_역할이_일반_유저면_Facade에서_403() {
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
        chatUserUseCase.update(activeUser(HOST_ID, "호스트", Role.USER));

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
        chatUserUseCase.update(activeUser(HOST_ID, "호스트", Role.USER));

        client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                .headers(login(HOST_ID, Role.USER)::applyTo)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void 수신_확인하면_200_RsData로_반영한_위치를_돌려주고_같은_값이나_낮은_값도_200이다() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", null);
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        ChatMessagePageResponse page = messages(guest, chatRoomId);
        assertThat(page.participationVersion()).isEqualTo(1);
        assertThat(page.visibleFromSequence()).isEqualTo(1);

        RsData<ChatReceiptResponse> received = markReceived(guest, chatRoomId,
                new ReceivedChatRoomRequest(page.participationVersion(), 2L));
        assertThat(received.statusCode()).isEqualTo(200);
        assertThat(received.message()).isEqualTo("수신 위치를 반영했습니다.");
        assertThat(received.data()).isEqualTo(new ChatReceiptResponse(chatRoomId, 1, 2));

        assertThat(markReceived(guest, chatRoomId, new ReceivedChatRoomRequest(1, 1L)).data().lastReceivedSequence())
                .isEqualTo(2);
        assertThat(lastReceivedSequence(chatRoomId, GUEST_ID)).isEqualTo(2L);
        assertThat(lastReceivedSequence(chatRoomId, HOST_ID)).isNull();
    }

    @Test
    void 수신_확인은_본문의_회원_ID가_아니라_로그인한_회원의_위치만_바꾼다() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);

        client.put().uri("/api/v1/chats/{id}/received", chatRoomId)
                .headers(login(GUEST_ID, Role.VERIFIED_INFLUENCER)::applyTo)
                .body(Map.of("userId", HOST_ID, "participationVersion", 1, "receivedSequence", 1))
                .exchange()
                .expectStatus().isOk();

        assertThat(lastReceivedSequence(chatRoomId, GUEST_ID)).isEqualTo(1L);
        assertThat(lastReceivedSequence(chatRoomId, HOST_ID)).isNull();
    }

    @Test
    void 수신_확인_오류는_ProblemDetail의_code로_응답한다() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        assertReceiveFails(guest, chatRoomId, new ReceivedChatRoomRequest(2, 1L), HttpStatus.CONFLICT,
                "STALE_PARTICIPATION");
        assertReceiveFails(guest, chatRoomId, new ReceivedChatRoomRequest(1, 2L), HttpStatus.BAD_REQUEST,
                "RECEIVED_SEQUENCE_OUT_OF_RANGE");
        assertReceiveFails(guest, chatRoomId, new ReceivedChatRoomRequest(1, null), HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST");
        assertReceiveFails(guest, chatRoomId, new ReceivedChatRoomRequest(-1, 1L), HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST");
        assertReceiveFails(guest, chatRoomId, new ReceivedChatRoomRequest(0, 1L), HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST");
        assertReceiveFails(guest, chatRoomId + 1000, new ReceivedChatRoomRequest(1, 1L), HttpStatus.NOT_FOUND,
                "CHAT_ROOM_NOT_FOUND");
        assertReceiveFails(login(OTHER_INFLUENCER_ID, Role.INFLUENCER), chatRoomId,
                new ReceivedChatRoomRequest(1, 1L), HttpStatus.FORBIDDEN, "NOT_CHAT_ROOM_MEMBER");
        assertReceiveFails(login(USER_ID, Role.USER), chatRoomId, new ReceivedChatRoomRequest(1, 1L),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertThat(lastReceivedSequence(chatRoomId, GUEST_ID)).isNull();

        client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                .headers(guest::applyTo)
                .exchange()
                .expectStatus().isNoContent();
        assertReceiveFails(guest, chatRoomId, new ReceivedChatRoomRequest(1, 1L), HttpStatus.FORBIDDEN,
                "NOT_CHAT_ROOM_MEMBER");
    }

    @Test
    void 메시지_이력의_모든_페이지에_참여_세대와_조회_시작점이_있다_커서_페이지와_빈_페이지도() {
        Long chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        for (int i = 0; i < 3; i++) {
            chatMessageUseCase.send(chatRoomId, HOST_ID, "메시지 " + i, null);
        }
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        Map<String, Object> first = rawMessagesPage(guest, "/api/v1/chats/{id}/messages?size=2", chatRoomId);
        assertThat(first.get("nextCursor")).isEqualTo(2);
        Map<String, Object> cursorPage = rawMessagesPage(guest, "/api/v1/chats/{id}/messages?size=2&cursor=2",
                chatRoomId);
        assertThat(cursorPage.get("messages")).asInstanceOf(LIST).hasSize(1);
        assertThat(cursorPage.get("nextCursor")).isNull();
        Map<String, Object> emptyPage = rawMessagesPage(guest, "/api/v1/chats/{id}/messages?size=2&cursor=1",
                chatRoomId);
        assertThat(emptyPage.get("messages")).asInstanceOf(LIST).isEmpty();
        assertThat(emptyPage.get("nextCursor")).isNull();

        // 기본 타입 필드는 빠져도 역직렬화하면 0이 되므로 JSON에 키가 있는지 직접 본다
        for (Map<String, Object> page : List.of(first, cursorPage, emptyPage)) {
            assertThat(page).containsEntry("participationVersion", 1).containsEntry("visibleFromSequence", 1);
        }
    }

    private Map<String, Object> rawMessagesPage(E2ESession session, String uri, Long chatRoomId) {
        RsData<Map<String, Object>> page = client.get().uri(uri, chatRoomId)
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<Map<String, Object>>>() {
                })
                .returnResult().getResponseBody();
        assertThat(page).isNotNull();
        return page.data();
    }

    private RsData<ChatReceiptResponse> markReceived(E2ESession session, Long chatRoomId,
            ReceivedChatRoomRequest request) {
        RsData<ChatReceiptResponse> response = client.put().uri("/api/v1/chats/{id}/received", chatRoomId)
                .headers(session::applyTo)
                .body(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatReceiptResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private void assertReceiveFails(E2ESession session, Long chatRoomId, ReceivedChatRoomRequest request,
            HttpStatus status, String code) {
        ProblemDetail problem = client.put().uri("/api/v1/chats/{id}/received", chatRoomId)
                .headers(session::applyTo)
                .body(request)
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", code);
    }

    private ChatMessagePageResponse messages(E2ESession session, Long chatRoomId) {
        RsData<ChatMessagePageResponse> page = client.get().uri("/api/v1/chats/{id}/messages", chatRoomId)
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatMessagePageResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(page).isNotNull();
        return page.data();
    }

    private Long lastReceivedSequence(Long chatRoomId, Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow().getLastReceivedSequence();
    }

    private E2ESession login(Long userId, Role role) {
        return E2ESession.login(client, userId, role);
    }

    private ChatRoomResponse createRoom(E2ESession session, Long targetUserId, HttpStatus expectedStatus) {
        RsData<ChatRoomResponse> room = client.post().uri("/api/v1/chats")
                .headers(session::applyTo)
                .body(new CreateChatRoomRequest(targetUserId))
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(new ParameterizedTypeReference<RsData<ChatRoomResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(room).isNotNull();
        // 본문의 statusCode는 실제 HTTP 상태와 같다
        assertThat(room.statusCode()).isEqualTo(expectedStatus.value());
        return room.data();
    }

    private List<ChatRoomResponse> myRooms(E2ESession session) {
        RsData<List<ChatRoomResponse>> rooms = client.get().uri("/api/v1/chats")
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<List<ChatRoomResponse>>>() {
                })
                .returnResult().getResponseBody();
        assertThat(rooms).isNotNull();
        return rooms.data();
    }
}
