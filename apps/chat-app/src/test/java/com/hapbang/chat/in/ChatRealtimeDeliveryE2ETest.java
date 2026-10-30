package com.hapbang.chat.in;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static com.hapbang.chat.in.support.RealtimeStompClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.hapbang.chat.app.ChatConnectionRecheckService;
import com.hapbang.chat.app.ChatPresence;
import com.hapbang.chat.app.ChatRealtimeFacade;
import com.hapbang.chat.app.ChatRecipientStateReader;
import com.hapbang.chat.app.ChatRoomUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.LocalChatConnections;
import com.hapbang.chat.app.LocalRoomSubscriptions;
import com.hapbang.chat.app.RealtimeChatFanout;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.global.response.RsData;
import com.hapbang.chat.in.dto.CreateChatRoomRequest;
import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.support.E2ESession;
import com.hapbang.chat.in.support.RealtimeStompClient;
import com.hapbang.chat.in.support.RealtimeStompClient.Frame;
import com.hapbang.chat.in.support.RealtimeStompClient.RoomSubscription;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.redis.ChatRedisKeys;
import com.hapbang.chat.out.redis.RedisRealtimeChatFanout;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * 방 topic 실시간 경로(설계 4.2)를 실제 Redis와 STOMP 클라이언트로 검증한다. {@link RedisRealtimeChatFanout}으로 발행하면
 * 서버 채널에 알림 재료를, 방 채널에 메시지를 내고, 이 앱이 방 topic 구독의 참여 상태를 버전 확인 규칙으로 확인해 쓴다.
 * 메시지는 저장하지 않고 payload만 만들어 발행한다(구독자는 메시지 존재가 아니라 참여·자격·수신자 집합 버전만 확인한다).
 * <p>
 * 아닌 경우("받지 않는다")는 같은 발행에서 받아야 하는 회원이 받은 뒤에 관찰 구간을 두고 확인한다.
 */
@ChatE2ETest
@Import(RedisTestcontainersConfig.class)
class ChatRealtimeDeliveryE2ETest {

    private static final String NAMESPACE = "test-realtime-delivery-" + UUID.randomUUID();
    private static final String MESSAGES = "/user/queue/chats/messages";
    private static final String NOTIFICATIONS = "/user/queue/chats/notifications";

    @DynamicPropertySource
    static void realtimeProperties(DynamicPropertyRegistry registry) {
        registry.add("hapbang.chat.realtime.enabled", () -> "true");
        // 이 테스트는 구독자만 검증한다. 발행은 테스트가 직접 하므로 outbox 파이프라인은 끈다
        registry.add("hapbang.chat.delivery.pipeline.enabled", () -> "false");
        registry.add("hapbang.chat.redis.namespace", () -> NAMESPACE);
        // 방 채널 해제를 바로 확인한다
        registry.add("hapbang.chat.realtime.room.channel-grace", () -> "0s");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private LocalChatConnections localChatConnections;

    @Autowired
    private RealtimeChatFanout realtimeChatFanout;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private ChatConnectionRecheckService chatConnectionRecheckService;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ChatRealtimeFacade chatRealtimeFacade;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatRecipientStateReader chatRecipientStateReader;

    @Autowired
    private LocalRoomSubscriptions localRoomSubscriptions;

    @Autowired
    private ChatPresence chatPresence;

    @Autowired
    private ChatRedisKeys chatRedisKeys;

    private RestTestClient client;
    private RealtimeStompClient stomp;

    @BeforeEach
    void setUp() {
        // 앞 테스트의 연결이 남아 있으면 연결 종료·재동기화 검증이 섞인다
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
    void 운영_구현은_서버_채널에_먼저_발행하고_서버_채널의_구독자_수를_돌려준다() {
        assertThat(realtimeChatFanout).isInstanceOf(RedisRealtimeChatFanout.class);
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);

        // 서버 채널은 이 앱 하나가 늘 구독한다. 방 채널은 아무도 그 방을 열지 않아 0명이지만 성공이다
        assertThat(realtimeChatFanout.publishMessage(fanout(chatRoomId, 1, 1, null))).isEqualTo(1);
        assertThat(realtimeChatFanout.publishControl(recheck(UNKNOWN_ID))).isEqualTo(1);
    }

    @Test
    void 구독자가_없는_서버_채널에_발행하면_방_채널은_발행하지_않고_구독자_수_0을_돌려준다() {
        // 이 앱이 구독하지 않는 namespace. replica 1개에서 자기 구독이 끊긴 상황과 같다(realtime-fanout.md)
        RedisRealtimeChatFanout unsubscribed = new RedisRealtimeChatFanout(stringRedisTemplate, jsonMapper,
                new ChatRedisKeys("test-realtime-no-subscriber-" + UUID.randomUUID()));

        assertThat(unsubscribed.publishMessage(fanout(1L, 1, 1, null))).isZero();
        assertThat(unsubscribed.publishControl(recheck(HOST_ID))).isZero();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void 열어_둔_방_구독에만_방_topic으로_쓰고_보낸_사람_세션에만_전송_ID를_싣는다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession hostOpen = stomp.connect(HOST_ID);
        StompSession hostElsewhere = stomp.connect(HOST_ID);
        StompSession guestOpen = stomp.connect(GUEST_ID);
        StompSession guestElsewhere = stomp.connect(GUEST_ID);
        RoomSubscription hostRoom = stomp.subscribeRoom(hostOpen, chatRoomId, 1);
        RoomSubscription guestRoom = stomp.subscribeRoom(guestOpen, chatRoomId, 1);
        BlockingQueue<Frame<ChatMessageResponse>> hostOpenSent = stomp.subscribe(hostOpen, MESSAGES,
                ChatMessageResponse.class);
        BlockingQueue<Frame<ChatMessageResponse>> hostElsewhereSent = stomp.subscribe(hostElsewhere, MESSAGES,
                ChatMessageResponse.class);
        BlockingQueue<Frame<Map>> guestOpenNotices = stomp.subscribe(guestOpen, NOTIFICATIONS, Map.class);
        BlockingQueue<Frame<Map>> guestElsewhereNotices = stomp.subscribe(guestElsewhere, NOTIFICATIONS, Map.class);
        BlockingQueue<Frame<Map>> hostElsewhereNotices = stomp.subscribe(hostElsewhere, NOTIFICATIONS, Map.class);
        BlockingQueue<Frame<ChatMessageResponse>> guestSent = stomp.subscribe(guestElsewhere, MESSAGES,
                ChatMessageResponse.class);
        waitForSubscriptions();

        MessageFanout fanout = fanout(chatRoomId, 1, 1, "c-1");
        realtimeChatFanout.publishMessage(fanout);

        Frame<Map> toGuest = poll(guestRoom.frames());
        assertThat(toGuest.frameType()).isEqualTo("MESSAGE");
        assertThat(toGuest.payload()).containsEntry("messageId", 9_001).containsEntry("roomSequence", 1);
        assertThat(toGuest.payload().get("clientMessageId")).isNull();
        assertThat(toGuest.participationVersion()).isEqualTo("1");
        Frame<Map> toHost = poll(hostRoom.frames());
        assertThat(toHost.payload()).containsEntry("clientMessageId", "c-1");
        assertThat(toHost.participationVersion()).isEqualTo("1");
        // 전송 결과 확인은 보낸 사람 회원의 모든 연결에 회원별 큐로 간다
        for (BlockingQueue<Frame<ChatMessageResponse>> sent : List.of(hostOpenSent, hostElsewhereSent)) {
            Frame<ChatMessageResponse> confirmation = poll(sent);
            assertThat(confirmation.payload().clientMessageId()).isEqualTo("c-1");
            assertThat(confirmation.participationVersion()).isEqualTo("1");
        }
        // 방을 열지 않은 게스트 연결은 방별로 묶은 새 메시지 알림을 받는다(헤더 없이 payload의 세대)
        Frame<Map> notice = poll(guestElsewhereNotices);
        assertThat(notice.payload()).containsEntry("type", "ROOM_ACTIVITY")
                .containsEntry("chatRoomId", chatRoomId.intValue())
                .containsEntry("participationVersion", 1)
                .containsEntry("lastSequence", 1)
                .containsEntry("messageCount", 1);
        assertThat(notice.participationVersion()).isNull();
        assertThat(guestOpenNotices.poll(1_500, TimeUnit.MILLISECONDS)).as("방을 구독한 연결은 알림을 받지 않는다").isNull();
        assertThat(hostElsewhereNotices.poll(100, TimeUnit.MILLISECONDS)).as("자기가 보낸 메시지는 알리지 않는다").isNull();
        assertThat(guestSent.poll(100, TimeUnit.MILLISECONDS)).as("남의 메시지는 회원별 메시지 큐로 오지 않는다").isNull();
        assertThat(guestRoom.frames().poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void 참여와_세대가_맞지_않는_방_topic_구독은_연결을_닫지_않고_그_구독에_거절_프레임을_쓴다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        StompSession outsider = stomp.connect(OTHER_INFLUENCER_ID);

        Frame<Map> stale = poll(stomp.subscribeRoom(guest, chatRoomId, 2).frames());
        assertThat(stale.frameType()).isEqualTo("SUBSCRIPTION_REJECTED");
        assertThat(stale.payload()).containsEntry("type", "SUBSCRIPTION_REJECTED")
                .containsEntry("roomId", chatRoomId.intValue())
                .containsEntry("reason", "STALE_PARTICIPATION")
                .containsEntry("currentParticipationVersion", 1);
        Frame<Map> notMember = poll(stomp.subscribeRoom(outsider, chatRoomId, 1).frames());
        assertThat(notMember.payload()).containsEntry("reason", "NOT_MEMBER")
                .doesNotContainKey("currentParticipationVersion");
        Frame<Map> missing = poll(stomp.subscribeRoom(guest, 987_654L, 1).frames());
        assertThat(missing.payload()).containsEntry("reason", "ROOM_NOT_FOUND");

        // 거절된 구독 ID의 UNSUBSCRIBE는 오류·연결 종료 없이 무시한다
        RoomSubscription rejected = stomp.subscribeRoom(guest, chatRoomId, 3);
        poll(rejected.frames());
        rejected.subscription().unsubscribe();
        // 같은 연결로 맞는 세대를 다시 구독하면 받는다
        RoomSubscription accepted = stomp.subscribeRoom(guest, chatRoomId, 1);
        waitForSubscriptions();
        realtimeChatFanout.publishMessage(fanout(chatRoomId, 1, 1, null));
        assertThat(poll(accepted.frames()).frameType()).isEqualTo("MESSAGE");
        assertThat(guest.isConnected()).isTrue();
        assertThat(outsider.isConnected()).isTrue();
        assertThat(localRoomSubscriptions.subscriptions(chatRoomId)).hasSize(1);
    }

    @Test
    void 방_topic의_경로_형식을_어기거나_세대_헤더가_없으면_연결을_닫는다() throws Exception {
        StompSession noHeader = stomp.connect(GUEST_ID);
        StompSession wrongPath = stomp.connect(HOST_ID);

        noHeader.subscribe("/topic/chats/rooms/1", new StompSessionHandlerAdapter() {
        });
        wrongPath.subscribe("/topic/chats/other", new StompSessionHandlerAdapter() {
        });

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> !noHeader.isConnected() && !wrongPath.isConnected());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void 퇴장_뒤_저장된_메시지는_회수_신호_없이_구독이_남아_있어도_보내지_않고_구독을_회수한다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        RoomSubscription hostRoom = stomp.subscribeRoom(host, chatRoomId, 1);
        RoomSubscription guestRoom = stomp.subscribeRoom(guest, chatRoomId, 1);
        waitForSubscriptions();
        // 방 참여 캐시를 퇴장 전 버전으로 채운다
        realtimeChatFanout.publishMessage(fanout(chatRoomId, 1, 1, null));
        poll(hostRoom.frames());
        poll(guestRoom.frames());

        // 즉시 회수(Facade)를 거치지 않고 퇴장만 커밋한다. 구독은 남아 있다
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        int afterLeave = currentSetVersion(chatRoomId);
        realtimeChatFanout.publishMessage(fanout(chatRoomId, 3, afterLeave, null));

        assertThat(poll(hostRoom.frames()).payload()).containsEntry("roomSequence", 3);
        Frame<Map> revoked = poll(guestRoom.frames());
        assertThat(revoked.frameType()).isEqualTo("SUBSCRIPTION_REVOKED");
        assertThat(revoked.payload()).containsEntry("reason", "LEFT");
        assertThat(guestRoom.frames().poll(500, TimeUnit.MILLISECONDS)).as("퇴장 뒤 메시지").isNull();
        assertThat(localRoomSubscriptions.subscriptionsOf(chatRoomId, GUEST_ID)).isEmpty();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void REST로_나가면_커밋_뒤_바로_그_방_구독을_회수한다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        RoomSubscription guestRoom = stomp.subscribeRoom(guest, chatRoomId, 1);
        waitForSubscriptions();

        client.delete().uri("/api/v1/chats/{id}/members/me", chatRoomId)
                .headers(E2ESession.login(client, GUEST_ID, Role.VERIFIED_INFLUENCER)::applyTo)
                .exchange()
                .expectStatus().isNoContent();

        Frame<Map> revoked = poll(guestRoom.frames());
        assertThat(revoked.frameType()).isEqualTo("SUBSCRIPTION_REVOKED");
        assertThat(revoked.payload()).containsEntry("reason", "LEFT");
        assertThat(guest.isConnected()).isTrue();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void 재입장으로_세대가_바뀌면_옛_구독을_현재_세대와_함께_회수하고_새_세대_구독은_가시_범위_안만_받는다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        RoomSubscription oldGeneration = stomp.subscribeRoom(guest, chatRoomId, 1);
        waitForSubscriptions();
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
        ChatRoomMember rejoined = chatRoomMemberRepository
                .findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, GUEST_ID).orElseThrow();
        assertThat(rejoined.getParticipationVersion()).isEqualTo(2);
        int version = currentSetVersion(chatRoomId);
        long visible = rejoined.getVisibleFromSequence();

        realtimeChatFanout.publishMessage(fanout(chatRoomId, visible, version, null));
        Frame<Map> revoked = poll(oldGeneration.frames());
        assertThat(revoked.payload()).containsEntry("reason", "STALE_PARTICIPATION")
                .containsEntry("currentParticipationVersion", 2);
        assertThat(oldGeneration.frames().poll(300, TimeUnit.MILLISECONDS)).isNull();

        RoomSubscription newGeneration = stomp.subscribeRoom(guest, chatRoomId, 2);
        waitForSubscriptions();
        realtimeChatFanout.publishMessage(fanout(chatRoomId, visible - 1, version, null));
        realtimeChatFanout.publishMessage(fanout(chatRoomId, visible + 1, version, null));
        Frame<Map> received = poll(newGeneration.frames());
        assertThat(received.payload()).containsEntry("roomSequence", (int) (visible + 1));
        assertThat(received.participationVersion()).isEqualTo("2");
        assertThat(newGeneration.frames().poll(300, TimeUnit.MILLISECONDS)).as("가시 범위 밖").isNull();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void 구독한_뒤_채팅_자격을_잃은_회원의_구독에는_쓰지_않는다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        RoomSubscription hostRoom = stomp.subscribeRoom(host, chatRoomId, 1);
        RoomSubscription guestRoom = stomp.subscribeRoom(guest, chatRoomId, 1);
        waitForSubscriptions();
        // 회원 이벤트 리스너를 거치지 않아 연결은 열린 채로 남는다. 방 참여 캐시는 아직 읽지 않았다
        chatUserUseCase.update(userDto(GUEST_ID, "게스트", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED));

        realtimeChatFanout.publishMessage(fanout(chatRoomId, 1, 1, null));

        assertThat(poll(hostRoom.frames()).payload()).containsEntry("roomSequence", 1);
        assertThat(guestRoom.frames().poll(500, TimeUnit.MILLISECONDS)).isNull();
        assertThat(guest.isConnected()).isTrue();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void 같은_전달_작업이_다시_와도_같은_messageId_roomSequence_세대로_보내_FE가_중복을_거른다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        RoomSubscription guestRoom = stomp.subscribeRoom(guest, chatRoomId, 1);
        waitForSubscriptions();

        // PUBLISH 뒤 체크포인트 기록 전 종료로 같은 작업이 다시 발행된 경우. 서버는 중복을 막지 않는다
        MessageFanout fanout = fanout(chatRoomId, 7, 1, "c-7");
        realtimeChatFanout.publishMessage(fanout);
        realtimeChatFanout.publishMessage(fanout);

        for (int i = 0; i < 2; i++) {
            Frame<Map> toGuest = poll(guestRoom.frames());
            assertThat(toGuest.payload()).containsEntry("messageId", 9_007).containsEntry("roomSequence", 7);
            assertThat(toGuest.participationVersion()).isEqualTo("1");
        }
        assertThat(guestRoom.frames().poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void 방_채널은_첫_로컬_구독에서_구독하고_마지막_구독이_빠지면_놓는다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        String roomChannel = chatRedisKeys.roomChannel(chatRoomId);
        StompSession guest = stomp.connect(GUEST_ID);
        StompSession host = stomp.connect(HOST_ID);
        assertThat(roomChannelSubscribers(roomChannel)).isZero();

        RoomSubscription first = stomp.subscribeRoom(guest, chatRoomId, 1);
        RoomSubscription second = stomp.subscribeRoom(host, chatRoomId, 1);
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> roomChannelSubscribers(roomChannel) == 1);

        first.subscription().unsubscribe();
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1))
                .until(() -> roomChannelSubscribers(roomChannel) == 1);
        second.subscription().unsubscribe();
        // 이 테스트는 해제 유예를 0으로 둔다
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> roomChannelSubscribers(roomChannel) == 0);
        assertThat(localRoomSubscriptions.subscriptions(chatRoomId)).isEmpty();
    }

    @Test
    void 연결을_닫으면_그_연결의_방_구독과_방_채널도_정리한다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        String roomChannel = chatRedisKeys.roomChannel(chatRoomId);
        StompSession guest = stomp.connect(GUEST_ID);
        stomp.subscribeRoom(guest, chatRoomId, 1);
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> roomChannelSubscribers(roomChannel) == 1);

        guest.disconnect();

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> roomChannelSubscribers(roomChannel) == 0);
        assertThat(localRoomSubscriptions.subscriptions(chatRoomId)).isEmpty();
    }

    @Test
    void 연결이_열리면_Redis_접속_정보에_넣고_마지막_연결이_닫히면_뺀다() throws Exception {
        StompSession first = stomp.connect(GUEST_ID);
        StompSession second = stomp.connect(GUEST_ID);
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> chatPresence.onlineAmong(List.of(GUEST_ID, HOST_ID)).equals(Set.of(GUEST_ID)));

        first.disconnect();
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1))
                .until(() -> chatPresence.onlineAmong(List.of(GUEST_ID)).equals(Set.of(GUEST_ID)));
        second.disconnect();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> chatPresence.onlineAmong(List.of(GUEST_ID)).isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 재동기화_신호는_FE와_합의한_JSON_형식으로_sync_큐에_보낸다() throws Exception {
        StompSession guest = stomp.connect(GUEST_ID);
        BlockingQueue<Frame<Map>> sync = stomp.subscribe(guest, "/user/queue/chats/sync", Map.class);
        waitForSubscriptions();

        chatRealtimeFacade.onMessageSubscriptionRecovered();

        Frame<Map> frame = poll(sync);
        assertThat(frame.payload()).containsOnlyKeys("type", "reason", "occurredAt")
                .containsEntry("type", "RESYNC")
                .containsEntry("reason", "REALTIME_RESUBSCRIBED");
        assertThat((String) frame.payload().get("occurredAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 버린_방_메시지의_DELIVERY_DROPPED_재동기화는_그_방_구독_회원에게만_보낸다() throws Exception {
        Long chatRoomId = createRoom(HOST_ID, GUEST_ID);
        StompSession host = stomp.connect(HOST_ID);
        StompSession guest = stomp.connect(GUEST_ID);
        BlockingQueue<Frame<Map>> hostSync = stomp.subscribe(host, "/user/queue/chats/sync", Map.class);
        BlockingQueue<Frame<Map>> guestSync = stomp.subscribe(guest, "/user/queue/chats/sync", Map.class);
        stomp.subscribeRoom(guest, chatRoomId, 1);
        waitForSubscriptions();

        chatRealtimeFacade.onMessageDropped(chatRoomId);

        Frame<Map> frame = poll(guestSync);
        assertThat(frame.payload()).containsOnlyKeys("type", "reason", "occurredAt")
                .containsEntry("type", "RESYNC")
                .containsEntry("reason", "DELIVERY_DROPPED");
        assertThat(guestSync.poll(300, TimeUnit.MILLISECONDS)).as("구독 회원당 한 번").isNull();
        assertThat(hostSync.poll(100, TimeUnit.MILLISECONDS)).as("그 방을 구독하지 않은 연결").isNull();
    }

    @Test
    void RECHECK_USER는_지금도_채팅할_수_없는_회원의_로컬_연결을_모두_닫는다() throws Exception {
        StompSession first = stomp.connect(HOST_ID);
        StompSession second = stomp.connect(HOST_ID);
        StompSession other = stomp.connect(GUEST_ID);
        chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.SUSPENDED));

        closeStatusesOf(HOST_ID, 2, () -> realtimeChatFanout.publishControl(recheck(HOST_ID)));

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !first.isConnected() && !second.isConnected());
        assertThat(other.isConnected()).isTrue();
        assertLocalUsers(GUEST_ID);
    }

    @Test
    void 자격이_복구된_회원은_늦게_도착한_옛_RECHECK_USER로_닫지_않는다() throws Exception {
        StompSession restored = stomp.connect(HOST_ID);
        StompSession suspended = stomp.connect(GUEST_ID);
        chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.SUSPENDED));
        chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.ACTIVE));
        chatUserUseCase.update(userDto(GUEST_ID, "게스트", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED));

        // 제어 채널은 한 스레드가 순서대로 처리한다(control-workers 기본 1). 뒤 명령의 종료를 보면 앞 명령도 처리됐다
        closeStatusesOf(GUEST_ID, 1, () -> {
            realtimeChatFanout.publishControl(recheck(HOST_ID));
            realtimeChatFanout.publishControl(recheck(GUEST_ID));
        });

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !suspended.isConnected());
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(restored::isConnected);
        assertLocalUsers(HOST_ID);
    }

    @Test
    void 주기_재검사는_제어_명령이_없어도_채팅할_수_없는_회원의_연결을_닫는다() throws Exception {
        StompSession suspended = stomp.connect(HOST_ID);
        StompSession allowed = stomp.connect(GUEST_ID);
        chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.INFLUENCER, UserStatus.SUSPENDED));

        closeStatusesOf(HOST_ID, 1, chatConnectionRecheckService::recheckAllConnected);

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !suspended.isConnected());
        assertThat(allowed.isConnected()).isTrue();
        assertLocalUsers(GUEST_ID);
    }

    @Test
    void RECHECK_USER는_강등된_회원의_로컬_연결도_모두_닫는다() throws Exception {
        StompSession first = stomp.connect(HOST_ID);
        StompSession second = stomp.connect(HOST_ID);
        StompSession other = stomp.connect(GUEST_ID);
        // 인플루언서 → 일반 유저 강등. 회원 이벤트 리스너를 거치지 않으므로 RECHECK_USER 수신 전에는 연결이 열려 있다
        chatUserUseCase.update(userDto(HOST_ID, "호스트", Role.USER, UserStatus.ACTIVE));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1))
                .until(() -> first.isConnected() && second.isConnected());

        closeStatusesOf(HOST_ID, 2, () -> realtimeChatFanout.publishControl(recheck(HOST_ID)));

        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until(() -> !first.isConnected() && !second.isConnected());
        assertThat(other.isConnected()).isTrue();
        assertLocalUsers(GUEST_ID);
    }

    private static MessageFanout fanout(Long chatRoomId, long roomSequence, int setVersion,
            String senderClientMessageId) {
        long messageId = 9_000 + roomSequence;
        ChatMessageResponse message = new ChatMessageResponse(messageId, chatRoomId, roomSequence, HOST_ID,
                ChatMessageType.TEXT, "실시간 " + roomSequence, NOW, null, null, null, null);
        return new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, chatRoomId, setVersion, message,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, senderClientMessageId);
    }

    private int currentSetVersion(Long chatRoomId) {
        return chatRecipientStateReader.findRoomMembership(chatRoomId).setVersion();
    }

    /** Redis의 그 채널 구독자 수(이 namespace는 이 앱만 쓴다) */
    private long roomChannelSubscribers(String channel) {
        Long count = stringRedisTemplate.execute(new DefaultRedisScript<>(
                "return redis.call('PUBSUB', 'NUMSUB', ARGV[1])[2]", Long.class), List.of(), channel);
        return count == null ? 0 : count;
    }

    private static ControlFanout recheck(Long userId) {
        return new ControlFanout(UUID.randomUUID(), ChatControlType.RECHECK_USER, userId, 1L,
                ChatControlReason.MEMBER_MODIFIED);
    }

    private static <T> Frame<T> poll(BlockingQueue<Frame<T>> queue) throws InterruptedException {
        Frame<T> frame = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(frame).as("제한 시간 안에 받은 프레임").isNotNull();
        return frame;
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

    /** 서버의 로컬 연결 목록이 정확히 이 회원들만 남을 때까지 기다린다 */
    private void assertLocalUsers(Long... userIds) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                .until(() -> localChatConnections.connectedUserIds().equals(Set.of(userIds)));
    }

    /**
     * 동작을 실행한 뒤 그 회원의 연결이 서버에서 정확히 {@code expected}개 닫히는지 확인한다.
     * 기대한 수만큼 닫힌 뒤에도 잠깐 관찰해 더 닫히지 않는지 본다(앞 테스트의 연결이 섞이지 않았는지).
     */
    private List<CloseStatus> closeStatusesOf(Long userId, int expected, Runnable action) throws InterruptedException {
        BlockingQueue<CloseStatus> disconnects = new LinkedBlockingQueue<>();
        ApplicationListener<SessionDisconnectEvent> listener = event -> {
            if (event.getUser() != null && String.valueOf(userId).equals(event.getUser().getName())) {
                disconnects.add(event.getCloseStatus());
            }
        };
        applicationContext.addApplicationListener(listener);
        try {
            action.run();
            List<CloseStatus> closeStatuses = new ArrayList<>();
            for (int i = 0; i < expected; i++) {
                CloseStatus closeStatus = disconnects.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(closeStatus).as("%d번째 연결 종료", i + 1).isNotNull();
                closeStatuses.add(closeStatus);
            }
            assertThat(disconnects.poll(300, TimeUnit.MILLISECONDS)).as("기대보다 많은 연결 종료").isNull();
            assertThat(closeStatuses).allSatisfy(closeStatus -> {
                assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
                assertThat(closeStatus.getReason()).isEqualTo("CHAT_NOT_ALLOWED");
            });
            return closeStatuses;
        } finally {
            applicationContext.removeApplicationListener(listener);
        }
    }
}
