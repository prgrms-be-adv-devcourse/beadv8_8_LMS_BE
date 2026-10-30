package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatMessageUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.ReceivedChatRoomRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 수신 확인 계약(contracts/receipt-ack.md)의 HTTP 경계 중 {@code ChatApiE2ETest}가 다루지 않는 행과,
 * 같은 회원의 여러 기기·재입장 뒤 옛 세대 ACK를 실제 서버로 확인한다.
 */
@ChatE2ETest
class ChatReceiptApiE2ETest {

    private static final String RECEIVED_URI = "/api/v1/chats/{id}/received";

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    private RestTestClient client;
    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        chatRoomId = createRoom(login(HOST_ID, Role.INFLUENCER), GUEST_ID, HttpStatus.CREATED).chatRoomId();
        for (int i = 0; i < 3; i++) {
            chatMessageUseCase.send(chatRoomId, HOST_ID, "메시지 " + i, null);
        }
    }

    @Test
    void 로그인하지_않으면_401이다() {
        // CSRF 검사를 통과하는 쿠키·헤더 쌍을 실어 인증 거절만 본다
        client.put().uri(RECEIVED_URI, chatRoomId)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=anonymous-token")
                .header("X-XSRF-TOKEN", "anonymous-token")
                .body(new ReceivedChatRoomRequest(1, 1L))
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(lastReceivedSequence(GUEST_ID)).isNull();
    }

    @Test
    void 로그인_후_강등되거나_정지되면_세션_역할이_인플루언서여도_403_CHAT_NOT_ALLOWED다() {
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        chatUserUseCase.update(activeUser(GUEST_ID, "게스트", Role.USER));
        assertFails(guest, Map.of("participationVersion", 1, "receivedSequence", 1), HttpStatus.FORBIDDEN,
                "CHAT_NOT_ALLOWED");

        chatUserUseCase.update(userDto(GUEST_ID, "게스트", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED));
        assertFails(guest, Map.of("participationVersion", 1, "receivedSequence", 1), HttpStatus.FORBIDDEN,
                "CHAT_NOT_ALLOWED");
        assertThat(lastReceivedSequence(GUEST_ID)).isNull();
    }

    @Test
    void 필드_누락_음수_빈_본문_형식_오류는_400_INVALID_REQUEST다() {
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        assertFails(guest, Map.of("receivedSequence", 1), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertFails(guest, Map.of("participationVersion", 1), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertFails(guest, Map.of("participationVersion", 1, "receivedSequence", -1), HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST");
        assertFails(guest, Map.of(), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertFails(guest, Map.of("participationVersion", 1, "receivedSequence", "abc"), HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST");
        assertThat(lastReceivedSequence(GUEST_ID)).isNull();
    }

    @Test
    void 오류_응답의_status_code_detail이_계약과_같다() {
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        ProblemDetail stale = assertFails(guest, Map.of("participationVersion", 2, "receivedSequence", 1),
                HttpStatus.CONFLICT, "STALE_PARTICIPATION");
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(stale.getDetail()).isEqualTo("현재 참여 세대의 요청이 아닙니다. 채팅방 목록과 이력을 다시 불러와 주세요.");

        ProblemDetail outOfRange = assertFails(guest, Map.of("participationVersion", 1, "receivedSequence", 4),
                HttpStatus.BAD_REQUEST, "RECEIVED_SEQUENCE_OUT_OF_RANGE");
        assertThat(outOfRange.getStatus()).isEqualTo(400);
        assertThat(outOfRange.getDetail()).isEqualTo("수신 확인 순번이 볼 수 있는 메시지 범위를 벗어났습니다.");
    }

    @Test
    void 한_기기의_수신_확인은_다른_기기의_이력_복구를_막지_않고_낮은_확인은_서버_값을_줄이지_않는다() {
        E2ESession phone = login(GUEST_ID, Role.VERIFIED_INFLUENCER);
        E2ESession laptop = login(GUEST_ID, Role.VERIFIED_INFLUENCER);

        assertThat(markReceived(phone, new ReceivedChatRoomRequest(1, 3L)).lastReceivedSequence()).isEqualTo(3);

        // 서버 회원 커서가 3이어도 노트북은 자기 확인 위치(여기서는 0) 기준으로 가시 범위 전체를 다시 받는다
        ChatMessagePageResponse laptopPage = messages(laptop);
        assertThat(laptopPage.messages()).extracting(ChatMessageResponse::roomSequence).containsExactly(3L, 2L, 1L);
        assertThat(laptopPage.participationVersion()).isEqualTo(1);
        assertThat(laptopPage.visibleFromSequence()).isEqualTo(1);

        // 노트북이 자기 확인 위치(1)를 보내도 서버 값은 줄지 않고, 응답은 서버의 현재 값이다
        ChatReceiptResponse laptopAck = markReceived(laptop, new ReceivedChatRoomRequest(1, 1L));
        assertThat(laptopAck).isEqualTo(new ChatReceiptResponse(chatRoomId, 1, 3));
        assertThat(lastReceivedSequence(GUEST_ID)).isEqualTo(3L);
    }

    @Test
    void 재입장하면_옛_세대의_수신_확인은_409이고_이력은_새_세대와_조회_시작점을_알린다() {
        E2ESession guest = login(GUEST_ID, Role.VERIFIED_INFLUENCER);
        markReceived(guest, new ReceivedChatRoomRequest(1, 3L));
        client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                .headers(guest::applyTo)
                .exchange()
                .expectStatus().isNoContent();
        createRoom(guest, HOST_ID, HttpStatus.OK);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "재입장 뒤", null);

        ChatMessagePageResponse page = messages(guest);
        assertThat(page.participationVersion()).isEqualTo(2);
        assertThat(page.messages()).allSatisfy(
                message -> assertThat(message.roomSequence()).isGreaterThanOrEqualTo(page.visibleFromSequence()));
        assertThat(page.visibleFromSequence()).isGreaterThan(3);
        assertThat(lastReceivedSequence(GUEST_ID)).isNull();

        long latest = page.messages().getFirst().roomSequence();
        assertFails(guest, Map.of("participationVersion", 1, "receivedSequence", latest), HttpStatus.CONFLICT,
                "STALE_PARTICIPATION");
        assertFails(guest, Map.of("participationVersion", 2, "receivedSequence", 3), HttpStatus.BAD_REQUEST,
                "RECEIVED_SEQUENCE_OUT_OF_RANGE");
        assertThat(lastReceivedSequence(GUEST_ID)).isNull();

        assertThat(markReceived(guest, new ReceivedChatRoomRequest(2, latest)))
                .isEqualTo(new ChatReceiptResponse(chatRoomId, 2, latest));
    }

    private ChatReceiptResponse markReceived(E2ESession session, ReceivedChatRoomRequest request) {
        RsData<ChatReceiptResponse> response = client.put().uri(RECEIVED_URI, chatRoomId)
                .headers(session::applyTo)
                .body(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatReceiptResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        assertThat(response.statusCode()).isEqualTo(200);
        return response.data();
    }

    private ProblemDetail assertFails(E2ESession session, Object body, HttpStatus status, String code) {
        ProblemDetail problem = client.put().uri(RECEIVED_URI, chatRoomId)
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

    private ChatMessagePageResponse messages(E2ESession session) {
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

    private Long lastReceivedSequence(Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow().getLastReceivedSequence();
    }

    private E2ESession login(Long userId, Role role) {
        return E2ESession.login(client, userId, role);
    }
}
