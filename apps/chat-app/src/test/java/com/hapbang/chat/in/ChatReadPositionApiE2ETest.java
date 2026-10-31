package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatMessageUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.shared.member.domain.Role;

/**
 * 읽음 처리의 참여 세대 계약(contracts/read-position.md)의 HTTP 경계 중 {@code ChatApiE2ETest}가 다루지 않는 행을
 * 실제 서버로 확인한다: 전환 전 FE 본문(필드 없음) 호환, 재입장 뒤 옛 세대 요청, 퇴장·종료한 방.
 */
@ChatE2ETest
class ChatReadPositionApiE2ETest {

    private static final String READ_URI = "/api/v1/chats/{id}/read";

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    private RestTestClient client;
    private E2ESession host;
    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        host = login(HOST_ID, Role.INFLUENCER);
        chatRoomId = createRoom(host, GUEST_ID, HttpStatus.CREATED).chatRoomId();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", null);
    }

    @Test
    void 전환_전_FE처럼_participationVersion_필드_없이_보내도_현재_세대로_반영한다() {
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        markRead(guest, Map.of("lastReadSequence", 2));

        assertThat(lastReadSequence(GUEST_ID)).isEqualTo(2L);
    }

    @Test
    void participationVersion이_음수면_400_INVALID_REQUEST이고_바꾸지_않는다() {
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);
        long before = lastReadSequence(GUEST_ID);

        assertFails(guest, Map.of("lastReadSequence", 2, "participationVersion", -1), HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST");

        assertThat(lastReadSequence(GUEST_ID)).isEqualTo(before);
    }

    @Test
    void 재입장_뒤_늦게_온_옛_세대_읽음은_409이고_새_세대_읽음_위치를_올리지_않는다() {
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);
        leave(guest);
        createRoom(guest, HOST_ID, HttpStatus.OK);
        long rejoinedRead = lastReadSequence(GUEST_ID);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "재입장 뒤", null);
        long latest = rejoinedRead + 1;

        assertFails(guest, Map.of("lastReadSequence", latest, "participationVersion", 1), HttpStatus.CONFLICT,
                "STALE_PARTICIPATION");
        assertThat(lastReadSequence(GUEST_ID)).isEqualTo(rejoinedRead);

        markRead(guest, Map.of("lastReadSequence", latest, "participationVersion", 2));
        assertThat(lastReadSequence(GUEST_ID)).isEqualTo(latest);
    }

    @Test
    void 나간_방에_세대를_보내_읽음_처리하면_403_NOT_CHAT_ROOM_MEMBER다() {
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);
        long before = lastReadSequence(GUEST_ID);
        leave(guest);

        assertFails(guest, Map.of("lastReadSequence", 2, "participationVersion", 1), HttpStatus.FORBIDDEN,
                "NOT_CHAT_ROOM_MEMBER");
        assertThat(lastReadSequence(GUEST_ID)).isEqualTo(before);
    }

    @Test
    void 모두_나가_종료된_방에_읽음_처리하면_404_CHAT_ROOM_NOT_FOUND다() {
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);
        leave(guest);
        leave(host);

        assertFails(host, Map.of("lastReadSequence", 2, "participationVersion", 1), HttpStatus.NOT_FOUND,
                "CHAT_ROOM_NOT_FOUND");
    }

    private void markRead(E2ESession session, Object body) {
        client.put().uri(READ_URI, chatRoomId)
                .headers(session::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isNoContent();
    }

    private ProblemDetail assertFails(E2ESession session, Object body, HttpStatus status, String code) {
        ProblemDetail problem = client.put().uri(READ_URI, chatRoomId)
                .headers(session::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectBody(ProblemDetail.class)
                .returnResult().getResponseBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", code);
        return problem;
    }

    private void leave(E2ESession session) {
        client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                .headers(session::applyTo)
                .exchange()
                .expectStatus().isNoContent();
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
        return room.data();
    }

    private long lastReadSequence(Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow().getLastReadSequence();
    }

    private E2ESession login(Long userId, Role role) {
        return E2ESession.login(client, userId, role);
    }
}
