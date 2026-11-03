package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatMessageUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.ReadChatRoomRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.support.RealtimeStompClient.Frame;
import com.hapbang.chat.in.support.RealtimeStompClient.RoomSubscription;
import com.hapbang.shared.member.domain.Role;

/**
 * 메시지별 안 읽은 사람 수(설계 Q36)의 실시간 경로를 운영과 같은 경로(outbox → Streams → Pub/Sub → 방 topic, 커밋 뒤 서버 채널
 * 신호 → 방별 묶음)로 검증한다. 1:1 방이 1 → 0이 되는 경우(1:1 신규 기능)다.
 */
@ChatE2ETest
class ChatReadCountE2ETest {

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private LocalChatConnections localChatConnections;

    private RestTestClient client;
    private RealtimeStompClient stomp;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        stomp = new RealtimeStompClient(client, port, localChatConnections);
    }

    @AfterEach
    void tearDown() {
        stomp.close();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void 새_메시지_프레임은_전달_시점의_값을_싣고_상대가_읽으면_방_topic으로_묶은_갱신이_온다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = stomp.connect(HOST_ID);
        RoomSubscription hostRoom = stomp.subscribeRoom(host, chatRoomId, 1);
        waitForSubscriptions();

        chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "c-1");

        Frame<Map> message = poll(hostRoom.frames());
        assertThat(message.frameType()).isEqualTo("MESSAGE");
        assertThat(message.payload()).containsEntry("roomSequence", 1).containsEntry("unreadMemberCount", 1);
        assertThat(((Number) message.payload().get("readCountVersion")).longValue()).isPositive();

        ChatMessagePageResponse page = messages(chatRoomId, GUEST_ID);
        assertThat(page.messages().getFirst().unreadMemberCount()).isEqualTo(1);
        assertThat(page.readCountVersion()).isPositive();

        client.put().uri("/api/v1/chats/{id}/read", chatRoomId)
                .headers(E2ESession.of(GUEST_ID, Role.VERIFIED_INFLUENCER)::applyTo)
                .body(new ReadChatRoomRequest(1L, 1))
                .exchange()
                .expectStatus().isNoContent();

        Frame<Map> update = poll(hostRoom.frames());
        assertThat(update.frameType()).isEqualTo("READ_COUNTS");
        assertThat(update.participationVersion()).isEqualTo("1");
        assertThat(update.payload()).containsEntry("type", "READ_COUNTS")
                .containsEntry("roomId", chatRoomId.intValue())
                .containsEntry("fromSequence", 1)
                .containsEntry("toSequence", 1)
                .containsEntry("counts", List.of());
        assertThat(((Number) update.payload().get("readCountVersion")).longValue())
                .isGreaterThan(page.readCountVersion());
        assertThat(messages(chatRoomId, HOST_ID).messages().getFirst().unreadMemberCount()).isZero();

        // 같은 위치를 다시 읽으면 바뀐 것이 없어 갱신이 오지 않는다
        client.put().uri("/api/v1/chats/{id}/read", chatRoomId)
                .headers(E2ESession.of(GUEST_ID, Role.VERIFIED_INFLUENCER)::applyTo)
                .body(new ReadChatRoomRequest(1L, 1))
                .exchange()
                .expectStatus().isNoContent();
        assertThat(hostRoom.frames().poll(1_500, TimeUnit.MILLISECONDS)).isNull();
    }

    private ChatMessagePageResponse messages(Long chatRoomId, Long userId) {
        RsData<ChatMessagePageResponse> response = client.get().uri("/api/v1/chats/{id}/messages", chatRoomId)
                .headers(E2ESession.of(userId, Role.INFLUENCER)::applyTo)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatMessagePageResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response.data();
    }

    private Long createRoom(Long userId, Long targetUserId) {
        RsData<ChatRoomResponse> room = client.post().uri("/api/v1/chats")
                .headers(E2ESession.of(userId, Role.INFLUENCER)::applyTo)
                .body(new CreateChatRoomRequest(targetUserId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<RsData<ChatRoomResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(room).isNotNull();
        return room.data().chatRoomId();
    }

    private static <T> Frame<T> poll(BlockingQueue<Frame<T>> queue) throws InterruptedException {
        Frame<T> frame = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(frame).as("제한 시간 안에 받은 프레임").isNotNull();
        return frame;
    }
}
