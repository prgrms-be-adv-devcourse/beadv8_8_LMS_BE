package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.hapbang.chat.app.ChatNotificationFacade;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.dto.ReceivedChatRoomRequest;
import com.hapbang.chat.in.dto.SendChatMessageRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.support.RealtimeStompClient.Frame;
import com.hapbang.chat.in.support.RealtimeStompClient.RoomSubscription;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.member.domain.Role;

/**
 * 직접 전달을 걷어낸 뒤 실제 경로(저장 트랜잭션의 outbox → relay → Redis Streams → consumer → Pub/Sub → 로컬 방 topic)만으로
 * 일반 메시지·시스템 안내·출연 알림이 방을 연 연결에 참여 세대 헤더와 함께 도착하고, 보낸 사람은 전송 결과 확인을, 방을 열지 않은
 * 상대 연결은 새 메시지 알림을 받는지 검증한다(회원 이벤트 Kafka 소비·전달 파이프라인·실시간 전파·수신 확인 통합 검증,
 * test-criteria "Pub/Sub·체크포인트"·"chat-app replica 1개", 설계 9장 "일반·시스템 메시지"·"수신 자격").
 * <p>
 * 같은 회원의 여러 연결, 로컬 세션이 없는 회원, 참여하지 않은 회원을 함께 둔다. 파이프라인 worker가 둘이라 서로 다른 메시지의
 * 도착 순서는 보장하지 않으므로 메시지별로 모아 비교한다.
 */
@ChatE2ETest
class ChatPipelineDeliveryE2ETest {

    private static final String MESSAGES = "/user/queue/chats/messages";
    private static final String NOTIFICATIONS = "/user/queue/chats/notifications";
    private static final long PIPELINE_SECONDS = 15;
    private static final LocalDateTime SCHEDULED_AT = LocalDateTime.of(2026, 12, 20, 21, 0);

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private LocalChatConnections localChatConnections;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatNotificationFacade chatNotificationFacade;


    private RestTestClient client;
    private RealtimeStompClient stomp;

    @BeforeEach
    void setUp() {
        assertThat(localChatConnections.connectedUserIds()).isEmpty();
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
    void 일반_메시지는_파이프라인으로_방을_연_연결에_방_topic으로_가고_보낸_사람은_결과_확인을_방을_열지_않은_상대_연결은_알림을_받는다()
            throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host1 = stomp.connect(HOST_ID);
        StompSession host2 = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        StompSession guestElsewhere = stomp.connect(GUEST_ID);
        StompSession outsider = stomp.connect(OTHER_INFLUENCER_ID);
        RoomSubscription hostRoom = stomp.subscribeRoom(host1, chatRoomId, 1);
        RoomSubscription guestRoom = stomp.subscribeRoom(guest, chatRoomId, 1);
        var host1Sent = stomp.subscribe(host1, MESSAGES, ChatMessageResponse.class);
        var host2Sent = stomp.subscribe(host2, MESSAGES, ChatMessageResponse.class);
        var guestSent = stomp.subscribe(guest, MESSAGES, ChatMessageResponse.class);
        var guestNotices = stomp.subscribe(guest, NOTIFICATIONS, Map.class);
        var guestElsewhereNotices = stomp.subscribe(guestElsewhere, NOTIFICATIONS, Map.class);
        var hostNotices = stomp.subscribe(host2, NOTIFICATIONS, Map.class);
        var outsiderSent = stomp.subscribe(outsider, MESSAGES, ChatMessageResponse.class);
        var outsiderNotices = stomp.subscribe(outsider, NOTIFICATIONS, Map.class);
        waitForSubscriptions();

        host1.send(sendPath(chatRoomId), new SendChatMessageRequest("파이프라인으로 갑니다", "pipe-1"));

        Frame<Map> toGuest = poll(guestRoom.frames());
        assertThat(toGuest.payload()).containsEntry("content", "파이프라인으로 갑니다").containsEntry("roomSequence", 1);
        assertThat(toGuest.payload().get("clientMessageId")).isNull();
        assertThat(toGuest.participationVersion()).isEqualTo("1");
        Frame<Map> toHost = poll(hostRoom.frames());
        assertThat(toHost.payload()).containsEntry("clientMessageId", "pipe-1");
        for (var sent : List.of(host1Sent, host2Sent)) {
            Frame<ChatMessageResponse> confirmation = poll(sent);
            assertThat(confirmation.payload().clientMessageId()).isEqualTo("pipe-1");
            assertThat(confirmation.participationVersion()).isEqualTo("1");
        }
        Frame<Map> notice = poll(guestElsewhereNotices);
        assertThat(notice.payload()).containsEntry("type", "ROOM_ACTIVITY").containsEntry("messageCount", 1)
                .containsEntry("lastSequence", 1).containsEntry("participationVersion", 1);

        assertNothingMore(1500, guestNotices, hostNotices, outsiderNotices);
        assertNothingMore(100, guestSent, outsiderSent, host1Sent, host2Sent, guestRoom.frames(), hostRoom.frames());
        awaitDeliveriesPublished();
    }

    /**
     * 퇴장하면 그 방 구독을 회수하고 퇴장 안내는 남은 사람에게만 간다. 나간 사람에게 보낸 출연 알림은 재입장 안내(재입장 전 집합)와
     * 알림(재입장 뒤 집합)으로 나뉜다. 재입장한 회원은 새 세대로 다시 구독해 재입장 뒤 메시지만 받고, 이력도 같다.
     */
    @Test
    @SuppressWarnings("rawtypes")
    void 퇴장_안내와_출연_알림으로_인한_재입장_안내와_알림이_파이프라인으로_정확한_대상과_세대로_간다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host1 = stomp.connect(HOST_ID);
        StompSession host2 = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        RoomSubscription host1Room = stomp.subscribeRoom(host1, chatRoomId, 1);
        RoomSubscription host2Room = stomp.subscribeRoom(host2, chatRoomId, 1);
        RoomSubscription guestRoom = stomp.subscribeRoom(guest, chatRoomId, 1);
        var guestNotices = stomp.subscribe(guest, NOTIFICATIONS, Map.class);
        waitForSubscriptions();

        host1.send(sendPath(chatRoomId), new SendChatMessageRequest("나가기 전", "before-leave"));
        assertThat(poll(guestRoom.frames()).participationVersion()).isEqualTo("1");
        poll(host1Room.frames());
        poll(host2Room.frames());

        // REST 퇴장: 게스트의 방 구독은 회수되고, 안내는 남은 사람(호스트의 두 연결)에게만 간다
        leave(chatRoomId, GUEST_ID);
        Frame<Map> revoked = poll(guestRoom.frames());
        assertThat(revoked.frameType()).isEqualTo("SUBSCRIPTION_REVOKED");
        assertThat(revoked.payload()).containsEntry("reason", "LEFT");
        for (RoomSubscription hostRoom : List.of(host1Room, host2Room)) {
            Frame<Map> leftNotice = poll(hostRoom.frames());
            assertThat(leftNotice.payload()).containsEntry("messageType", "SYSTEM")
                    .containsEntry("content", "게스트님이 나갔습니다.");
            assertThat(leftNotice.participationVersion()).isEqualTo("1");
        }
        assertNothingMore(500, guestRoom.frames());

        // 나간 게스트에게 출연 요청 알림: 재입장 안내는 남아 있던 호스트에게만, 알림 메시지는 호스트(세대 1)의 방 topic과
        // 재입장한 게스트(세대 2)의 새 메시지 알림으로 간다(게스트는 아직 새 세대로 구독하지 않았다)
        // 출연 이벤트는 Kafka 소비(테스트 설정은 끔)가 받아 Facade를 부른다. 같은 Facade를 직접 부른다
        chatNotificationFacade.notifyAppearanceRequestSent(new AppearanceRequestDto(10L, HOST_ID, GUEST_ID,
                "금요일 밤 합방", 50_000L, SCHEDULED_AT, SCHEDULED_AT.minusDays(7)));

        Map<String, Frame<Map>> byType = new HashMap<>();
        for (Frame<Map> frame : pollN(host1Room.frames(), 2)) {
            byType.put((String) frame.payload().get("messageType"), frame);
            assertThat(frame.participationVersion()).isEqualTo("1");
        }
        assertThat(byType).containsOnlyKeys("SYSTEM", "NOTIFICATION");
        assertThat(byType.get("SYSTEM").payload()).containsEntry("content", "게스트님이 다시 들어왔습니다.");
        pollN(host2Room.frames(), 2);
        Frame<Map> guestNotice = poll(guestNotices);
        assertThat(guestNotice.payload()).containsEntry("type", "ROOM_ACTIVITY")
                .containsEntry("participationVersion", 2).containsEntry("messageCount", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastMessage = (Map<String, Object>) guestNotice.payload().get("lastMessage");
        assertThat(lastMessage)
                .containsEntry("messageType", "NOTIFICATION")
                .containsEntry("messageId", byType.get("NOTIFICATION").payload().get("messageId"));

        // 새 세대로 다시 구독하면 재입장 뒤 일반 메시지를 받는다
        RoomSubscription rejoinedRoom = stomp.subscribeRoom(guest, chatRoomId, 2);
        waitForSubscriptions();
        host1.send(sendPath(chatRoomId), new SendChatMessageRequest("다시 오셨네요", "after-rejoin"));
        Frame<Map> afterRejoin = poll(rejoinedRoom.frames());
        assertThat(afterRejoin.payload()).containsEntry("content", "다시 오셨네요");
        assertThat(afterRejoin.participationVersion()).isEqualTo("2");
        assertThat(poll(host1Room.frames()).participationVersion()).isEqualTo("1");

        // 재입장한 게스트의 이력은 재입장 뒤 메시지(알림·일반)뿐이고, 재입장 전 메시지·퇴장/재입장 안내는 없다
        ChatMessagePageResponse guestPage = page(chatRoomId, GUEST_ID, null, 30);
        assertThat(guestPage.participationVersion()).isEqualTo(2);
        assertThat(guestPage.messages()).extracting(message -> (Object) message.messageId().intValue())
                .containsExactlyInAnyOrder(byType.get("NOTIFICATION").payload().get("messageId"),
                        afterRejoin.payload().get("messageId"));
        assertThat(guestPage.messages()).allSatisfy(message ->
                assertThat(message.roomSequence()).isGreaterThanOrEqualTo(guestPage.visibleFromSequence()));
        awaitDeliveriesPublished();
    }

    /**
     * 한 기기가 끊긴 동안 온 메시지는 다시 연결한 뒤 그 기기의 확인 위치부터 이력을 읽어 빠짐없이·중복 없이 복구한다.
     * 같은 회원의 다른 기기는 연결을 유지한 채 실시간으로 모두 받는다.
     */
    @Test
    @SuppressWarnings("rawtypes")
    void 끊긴_기기는_재연결_뒤_자기_확인_위치부터_이력으로_빠짐없이_중복없이_복구하고_다른_기기는_실시간으로_받는다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = stomp.connect(HOST_ID);
        StompSession guestStaying = stomp.connect(GUEST_ID);
        StompSession guestLeaving = stomp.connect(GUEST_ID);
        RoomSubscription hostRoom = stomp.subscribeRoom(host, chatRoomId, 1);
        RoomSubscription stayingRoom = stomp.subscribeRoom(guestStaying, chatRoomId, 1);
        RoomSubscription leavingRoom = stomp.subscribeRoom(guestLeaving, chatRoomId, 1);
        waitForSubscriptions();

        host.send(sendPath(chatRoomId), new SendChatMessageRequest("메시지 1", "m-1"));
        Frame<Map> first = poll(leavingRoom.frames());
        poll(stayingRoom.frames());
        poll(hostRoom.frames());
        long confirmed = ((Number) first.payload().get("roomSequence")).longValue();
        // 끊길 기기의 확인 위치는 1이다. 서버 회원 커서도 이 기기의 ACK로만 오른다
        assertThat(markReceived(chatRoomId, GUEST_ID, Integer.parseInt(first.participationVersion()), confirmed)
                .lastReceivedSequence()).isEqualTo(1);

        guestLeaving.disconnect();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !guestLeaving.isConnected());
        int missed = 5;
        for (int i = 2; i <= missed + 1; i++) {
            host.send(sendPath(chatRoomId), new SendChatMessageRequest("메시지 " + i, "m-" + i));
        }
        assertThat(pollN(stayingRoom.frames(), missed))
                .extracting(frame -> ((Number) frame.payload().get("roomSequence")).longValue())
                .containsExactlyInAnyOrder(2L, 3L, 4L, 5L, 6L);
        pollN(hostRoom.frames(), missed);
        awaitDeliveriesPublished();

        StompSession reconnected = stomp.connect(GUEST_ID);
        RoomSubscription reconnectedRoom = stomp.subscribeRoom(reconnected, chatRoomId, 1);
        waitForSubscriptions();
        // 재연결이 끊긴 동안의 메시지를 다시 밀어 주지 않는다. 복구는 이력 조회로 한다
        assertNothingMore(500, reconnectedRoom.frames());

        // 최신 페이지부터 작은 페이지로 확인 위치(1)에 닿을 때까지 읽는다(여러 페이지 대조)
        List<Long> recovered = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        while (true) {
            ChatMessagePageResponse page = page(chatRoomId, GUEST_ID, cursor, 2);
            pages++;
            assertThat(page.participationVersion()).isEqualTo(1);
            page.messages().stream().map(ChatMessageResponse::roomSequence)
                    .filter(sequence -> sequence > confirmed).forEach(recovered::add);
            boolean reachedConfirmed = page.messages().stream().anyMatch(m -> m.roomSequence() <= confirmed);
            if (reachedConfirmed || page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        assertThat(pages).isGreaterThan(1);
        assertThat(recovered).doesNotHaveDuplicates().containsExactlyInAnyOrder(2L, 3L, 4L, 5L, 6L);

        host.send(sendPath(chatRoomId), new SendChatMessageRequest("메시지 7", "m-7"));
        assertThat(poll(reconnectedRoom.frames()).payload()).containsEntry("roomSequence", 7);
        assertThat(poll(stayingRoom.frames()).payload()).containsEntry("roomSequence", 7);
        assertNothingMore(500, reconnectedRoom.frames(), stayingRoom.frames());
    }

    /**
     * 같은 전송 ID 재전송: 새 행·전달 작업·체크포인트를 만들지 않고, 보낸 사람(모든 기기)에게만 기존 메시지를 세대 헤더와
     * 함께 다시 보낸다. 상대는 메시지도 알림도 다시 받지 않고 안 읽은 수도 그대로다.
     */
    @Test
    @SuppressWarnings("rawtypes")
    void 같은_전송_ID_재전송은_새_행과_전달_작업_없이_보낸_사람에게만_세대_헤더로_돌아가고_상대의_알림과_안_읽은_수는_그대로다()
            throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host1 = stomp.connect(HOST_ID);
        StompSession host2 = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        StompSession guestElsewhere = stomp.connect(GUEST_ID);
        var host1Sent = stomp.subscribe(host1, MESSAGES, ChatMessageResponse.class);
        var host2Sent = stomp.subscribe(host2, MESSAGES, ChatMessageResponse.class);
        RoomSubscription guestRoom = stomp.subscribeRoom(guest, chatRoomId, 1);
        var guestNotices = stomp.subscribe(guestElsewhere, NOTIFICATIONS, Map.class);
        waitForSubscriptions();

        host1.send(sendPath(chatRoomId), new SendChatMessageRequest("한 번만", "resend-1"));
        Frame<ChatMessageResponse> original = poll(host1Sent);
        poll(host2Sent);
        poll(guestRoom.frames());
        poll(guestNotices);
        awaitDeliveriesPublished();
        Map<String, Long> before = rowCounts();
        long unreadBefore = unreadCount(GUEST_ID, chatRoomId);
        assertThat(unreadBefore).isEqualTo(1);

        host1.send(sendPath(chatRoomId), new SendChatMessageRequest("본문이 달라도", "resend-1"));

        for (var sent : List.of(host1Sent, host2Sent)) {
            Frame<ChatMessageResponse> resent = poll(sent);
            assertThat(resent.payload().messageId()).isEqualTo(original.payload().messageId());
            assertThat(resent.payload().content()).isEqualTo("한 번만");
            assertThat(resent.payload().clientMessageId()).isEqualTo("resend-1");
            assertThat(resent.participationVersion()).isEqualTo("1");
        }
        assertNothingMore(1500, guestRoom.frames(), guestNotices);
        assertNothingMore(100, host1Sent, host2Sent);
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(unreadCount(GUEST_ID, chatRoomId)).isEqualTo(unreadBefore);
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (String table : List.of("chat_message", "chat_outbox", "chat_publish_checkpoint", "chat_recipient_set")) {
            counts.put(table, jdbcTemplate.queryForObject("select count(*) from chats." + table, Long.class));
        }
        return counts;
    }

    private static <T> Frame<T> poll(BlockingQueue<Frame<T>> queue) throws InterruptedException {
        Frame<T> frame = queue.poll(PIPELINE_SECONDS, TimeUnit.SECONDS);
        assertThat(frame).as("파이프라인으로 받을 프레임").isNotNull();
        return frame;
    }

    private static <T> List<Frame<T>> pollN(BlockingQueue<Frame<T>> queue, int count) throws InterruptedException {
        List<Frame<T>> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            frames.add(poll(queue));
        }
        return frames;
    }

    @SafeVarargs
    private static void assertNothingMore(long millis, BlockingQueue<? extends Frame<?>>... queues)
            throws InterruptedException {
        Thread.sleep(millis);
        for (BlockingQueue<? extends Frame<?>> queue : queues) {
            assertThat(queue).as("더 오면 안 되는 프레임").isEmpty();
        }
    }

    private void awaitDeliveriesPublished() {
        await().atMost(Duration.ofSeconds(PIPELINE_SECONDS)).until(() -> jdbcTemplate.queryForObject(
                "select count(*) from chats.chat_publish_checkpoint where status <> 'PUBLISHED'", Long.class) == 0);
    }

    private static String sendPath(Long chatRoomId) {
        return "/pub/chats/" + chatRoomId + "/messages";
    }

    private Long createRoom(Long userId, Long targetUserId) {
        RsData<ChatRoomResponse> room = client.post().uri("/api/v1/chats")
                .headers(E2ESession.login(client, userId, Role.INFLUENCER)::applyTo)
                .body(new CreateChatRoomRequest(targetUserId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(new ParameterizedTypeReference<RsData<ChatRoomResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(room).isNotNull();
        return room.data().chatRoomId();
    }

    private void leave(Long chatRoomId, Long userId) {
        client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                .headers(E2ESession.login(client, userId, Role.INFLUENCER)::applyTo)
                .exchange()
                .expectStatus().isNoContent();
    }

    private ChatMessagePageResponse page(Long chatRoomId, Long userId, Long cursor, int size) {
        RsData<ChatMessagePageResponse> page = client.get()
                .uri(cursor == null ? "/api/v1/chats/{id}/messages?size={size}"
                        : "/api/v1/chats/{id}/messages?size={size}&cursor=" + cursor, chatRoomId, size)
                .header(HttpHeaders.COOKIE, E2ESession.login(client, userId, Role.INFLUENCER).cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatMessagePageResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(page).isNotNull();
        return page.data();
    }

    private ChatReceiptResponse markReceived(Long chatRoomId, Long userId, int participationVersion, long sequence) {
        RsData<ChatReceiptResponse> response = client.put().uri("/api/v1/chats/{id}/received", chatRoomId)
                .headers(E2ESession.login(client, userId, Role.INFLUENCER)::applyTo)
                .body(new ReceivedChatRoomRequest(participationVersion, sequence))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<ChatReceiptResponse>>() {
                })
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response.data();
    }

    private long unreadCount(Long userId, Long chatRoomId) {
        RsData<List<ChatRoomResponse>> rooms = client.get().uri("/api/v1/chats")
                .header(HttpHeaders.COOKIE, E2ESession.login(client, userId, Role.INFLUENCER).cookieHeader())
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<RsData<List<ChatRoomResponse>>>() {
                })
                .returnResult().getResponseBody();
        assertThat(rooms).isNotNull();
        return rooms.data().stream().filter(room -> room.chatRoomId().equals(chatRoomId)).findFirst()
                .orElseThrow().unreadCount();
    }
}
