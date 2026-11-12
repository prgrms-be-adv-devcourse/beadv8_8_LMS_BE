package com.hapbang.auxiliary.notification.in.event;

import static com.hapbang.auxiliary.notification.in.event.MemberEventKafka.exceptionClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.chat.event.GroupMemberKickedEvent;
import com.hapbang.shared.chat.event.GroupMembersInvitedEvent;
import com.hapbang.shared.chat.event.GroupRoomClosedEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * N9 채팅 단체방 3종(#921)을 실제 Kafka로 받아 알림을 만드는지 본다(ND-127~ND-130). test-spec NT-GEN-24·08, NT-GEN-10·15(채팅 3종)
 * <ul>
 *     <li>여러 받는 사람: 레코드 하나가 처리 단위라 한 사람의 저장이 실패하면 레코드 전체를 다시 처리하고, 먼저 저장한 사람은 유일 키로
 *     건너뛴다(ND-019).</li>
 *     <li>필수 값 누락(다시 해도 같은 결과)은 재시도 없이 DLT, 받는 사람 복제본 없음(일시적일 수 있음)은 블로킹 재시도 뒤 DLT다(ND-010·ND-035).</li>
 * </ul>
 * 토픽·그룹은 이 클래스 전용(UUID), partition 하나라 마지막에 보낸 표식 이벤트가 처리되면 앞선 레코드도 끝난 것이다. 재시도는 2초 간격
 * 3회라 그 사이에 복제본을 넣어 일시적 오류가 회복되는지 볼 수 있다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationChatAlertTest.Topics.class})
class NotificationChatAlertTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String CHAT_TOPIC = "chat.events.v1.test-" + SUFFIX;
    private static final String CHAT_GROUP = "notification-chat-test-" + SUFFIX;
    private static final String CHAT_DLT = CHAT_TOPIC + "." + CHAT_GROUP + ".DLT";
    private static final int MAX_RETRIES = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(40);
    private static final Instant START_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AFTER_START = Instant.parse("2026-11-12T01:00:00Z");
    private static final String TITLE = "봄맞이 합방";
    /** 다른 통합 시험과 겹치지 않는 회원·대상 ID 범위 */
    private static final AtomicLong NEXT_ID = new AtomicLong(2_700_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.CHAT_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.alert.start-at", START_AT::toString);
    }

    @TestBean(name = NotificationKafkaConfig.CHAT_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings chatEventSettings;

    static NotificationKafkaConsumerSettings chatEventSettings() {
        return new NotificationKafkaConsumerSettings(CHAT_TOPIC, CHAT_GROUP, CHAT_DLT, MAX_RETRIES,
                Duration.ofSeconds(2), Duration.ofSeconds(2), NotificationKafkaConstants.DEAD_LETTER_SEND_TIMEOUT, 1,
                NotificationKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @MockitoSpyBean
    private NotificationUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    private AlertEventKafka kafka;

    @BeforeEach
    void setUp() {
        kafka = new AlertEventKafka(bootstrapServers);
        // 알림 소비자는 첫 Backfill 뒤에 시작한다(ND-078). 이 시험은 Backfill을 끄고 직접 시작한다
        MessageListenerContainer container = listenerRegistry.getListenerContainer(
                NotificationChatEventListener.LISTENER_ID);
        if (!container.isRunning()) {
            container.start();
        }
    }

    @AfterEach
    void tearDown() {
        kafka.close();
    }

    @Test
    void 초대는_참여한_회원마다_한_번만_만들고_같은_방의_다음_초대는_새로_만든다() {
        long room = nextId();
        long a = member(UserStatus.ACTIVE);
        long b = member(UserStatus.ACTIVE);
        GroupMembersInvitedEvent first = invited(room, nextId(), List.of(a, b));

        kafka.send(CHAT_TOPIC, String.valueOf(room), first);
        kafka.send(CHAT_TOPIC, String.valueOf(room), first);
        awaitProcessed();

        assertThat(rows(a)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "GROUP_MEMBERS_INVITED")
                    .containsEntry("source_event_type", "GroupMembersInvitedEvent")
                    .containsEntry("source_target_id", room + ":" + first.inviteRequestId())
                    .containsEntry("target_type", "CHAT_ROOM")
                    .containsEntry("target_id", String.valueOf(room))
                    .containsEntry("message", "'봄맞이 합방' 단체방에 초대되었습니다.");
            assertThat(occurredAt(row)).isEqualTo(AFTER_START);
        });
        assertThat(rows(b)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("source_target_id",
                        room + ":" + first.inviteRequestId()));

        // 나갔다가 다시 초대받으면 초대 요청 ID가 달라 새 알림이다
        GroupMembersInvitedEvent second = invited(room, nextId(), List.of(a));
        kafka.send(CHAT_TOPIC, String.valueOf(room), second);
        awaitProcessed();

        assertThat(rows(a)).extracting(row -> row.get("source_target_id"))
                .containsExactly(room + ":" + first.inviteRequestId(), room + ":" + second.inviteRequestId());
    }

    @Test
    void 강퇴는_강퇴된_회원에게_만들고_재초대_뒤_다시_강퇴되면_새로_만든다() {
        long room = nextId();
        long kicked = member(UserStatus.ACTIVE);
        GroupMemberKickedEvent firstKick = new GroupMemberKickedEvent(room, nextId(), TITLE, nextId(), kicked, 1,
                AFTER_START);
        GroupMemberKickedEvent secondKick = new GroupMemberKickedEvent(room, firstKick.recruitId(), TITLE,
                firstKick.hostUserId(), kicked, 2, AFTER_START.plusSeconds(60));

        kafka.send(CHAT_TOPIC, String.valueOf(room), firstKick);
        kafka.send(CHAT_TOPIC, String.valueOf(room), firstKick);
        kafka.send(CHAT_TOPIC, String.valueOf(room), secondKick);
        awaitProcessed();

        List<Map<String, Object>> rows = rows(kicked);
        assertThat(rows).extracting(row -> row.get("source_target_id"))
                .containsExactly(room + ":" + kicked + ":1", room + ":" + kicked + ":2");
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("kind", "GROUP_MEMBER_KICKED")
                .containsEntry("target_type", null)
                .containsEntry("target_id", null)
                .containsEntry("message", "'봄맞이 합방' 단체방에서 내보내졌습니다."));
    }

    @Test
    void 폐쇄는_호스트를_뺀_참여자에게_만들고_호스트만_있으면_알림_없이_커밋한다() {
        long room = nextId();
        long host = member(UserStatus.ACTIVE);
        long a = member(UserStatus.ACTIVE);
        long b = member(UserStatus.ACTIVE);
        long lonelyRoom = nextId();
        long lonelyHost = member(UserStatus.ACTIVE);

        kafka.send(CHAT_TOPIC, String.valueOf(room), closed(room, host, List.of(host, a, b)));
        kafka.send(CHAT_TOPIC, String.valueOf(lonelyRoom), closed(lonelyRoom, lonelyHost, List.of(lonelyHost)));
        awaitProcessed();

        assertThat(rows(host)).isEmpty();
        assertThat(rows(lonelyHost)).isEmpty();
        assertThat(List.of(a, b)).allSatisfy(receiver -> assertThat(rows(receiver)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("kind", "GROUP_ROOM_CLOSED")
                        .containsEntry("source_target_id", String.valueOf(room))
                        .containsEntry("target_type", "CHAT_ROOM")
                        .containsEntry("target_id", String.valueOf(room))
                        .containsEntry("message", "'봄맞이 합방' 단체방이 닫혔습니다. 새 메시지는 보낼 수 없습니다.")));
        assertThat(deadLetterKeysNow()).doesNotContain(String.valueOf(lonelyRoom));
    }

    @Test
    void 알림_시작_시각보다_이른_사건과_탈퇴_정지_회원은_알림_없이_건너뛴다() {
        long oldRoom = nextId();
        long room = nextId();
        long user = member(UserStatus.ACTIVE);
        long suspended = member(UserStatus.SUSPENDED);
        long withdrawn = member(UserStatus.WITHDRAWN);

        kafka.send(CHAT_TOPIC, String.valueOf(oldRoom), new GroupMembersInvitedEvent(oldRoom, nextId(), nextId(),
                TITLE, nextId(), List.of(user), Instant.parse("2025-12-31T23:59:59Z")));
        kafka.send(CHAT_TOPIC, String.valueOf(room), invited(room, nextId(), List.of(suspended, withdrawn)));
        awaitProcessed();

        assertThat(rows(user)).isEmpty();
        assertThat(rows(suspended)).isEmpty();
        assertThat(rows(withdrawn)).isEmpty();
        assertThat(deadLetterKeysNow()).doesNotContain(String.valueOf(oldRoom), String.valueOf(room));
    }

    @Test
    void 받는_사람_한_명의_복제본이_늦게_생기면_재시도로_회복하고_먼저_저장한_사람은_한_번만_만든다() {
        long room = nextId();
        long ready = member(UserStatus.ACTIVE);
        long late = nextId();

        kafka.send(CHAT_TOPIC, String.valueOf(room), invited(room, nextId(), List.of(ready, late)));
        // 첫 처리에서 늦은 회원의 복제본을 찾았고(없어 실패), 재시도 전에 복제본을 넣는다
        await().atMost(TIMEOUT).untilAsserted(() -> verify(users, atLeastOnce()).findForUpdate(eq(late)));
        save(late, UserStatus.ACTIVE);

        await().atMost(TIMEOUT).until(() -> rows(late).size() == 1);
        // 재시도 때 레코드 전체를 다시 처리했지만 먼저 저장한 회원은 유일 키로 건너뛰었다
        assertThat(rows(ready)).hasSize(1);
        assertThat(deadLetterKeysNow()).doesNotContain(String.valueOf(room));
    }

    @Test
    void 받는_사람_복제본이_계속_없으면_재시도_뒤_DLT로_보내고_다시_넣으면_빠진_사람만_만든다() {
        long room = nextId();
        long ready = member(UserStatus.ACTIVE);
        long missing = nextId();
        GroupMembersInvitedEvent event = invited(room, nextId(), List.of(ready, missing));

        kafka.send(CHAT_TOPIC, String.valueOf(room), event);

        ConsumerRecord<String, byte[]> deadLetter = new MemberEventKafka(bootstrapServers, CHAT_TOPIC)
                .awaitDeadLetter(CHAT_DLT, String.valueOf(room));
        // 일시적일 수 있는 오류라 처음 처리 + 재시도 3회를 거친 뒤에 DLT로 갔다(검증 실패와 다르다)
        assertThat(exceptionClasses(deadLetter)).contains("NotificationException")
                .doesNotContain("RejectedEventException");
        verify(users, atLeast(1 + MAX_RETRIES)).findForUpdate(eq(missing));
        assertThat(rows(ready)).hasSize(1);
        assertThat(rows(missing)).isEmpty();

        // 복제본이 생긴 뒤 DLT 레코드를 다시 넣으면 빠진 사람만 새로 만든다
        save(missing, UserStatus.ACTIVE);
        kafka.send(CHAT_TOPIC, String.valueOf(room), event);
        awaitProcessed();

        assertThat(rows(ready)).hasSize(1);
        assertThat(rows(missing)).hasSize(1);
    }

    @Test
    void 필수_값이_빠진_이벤트는_재시도_없이_DLT로_보낸다() {
        long user = member(UserStatus.ACTIVE);
        long noTitleRoom = nextId();
        long emptyRoom = nextId();

        kafka.send(CHAT_TOPIC, String.valueOf(noTitleRoom), new GroupMemberKickedEvent(noTitleRoom, nextId(), null,
                nextId(), user, 1, AFTER_START));
        kafka.send(CHAT_TOPIC, String.valueOf(emptyRoom), closed(emptyRoom, nextId(), List.of()));

        MemberEventKafka dlt = new MemberEventKafka(bootstrapServers, CHAT_TOPIC);
        // 다시 해도 같은 결과라 바로 DLT로 간다(재시도하지 않는 예외)
        assertThat(exceptionClasses(dlt.awaitDeadLetter(CHAT_DLT, String.valueOf(noTitleRoom))))
                .contains("RejectedEventException");
        assertThat(exceptionClasses(dlt.awaitDeadLetter(CHAT_DLT, String.valueOf(emptyRoom))))
                .contains("RejectedEventException");
        assertThat(rows(user)).isEmpty();
    }

    @Test
    void 읽을_수_없는_본문과_신뢰_패키지_밖_타입은_DLT로_보낸다() {
        long room = nextId();

        // 받는 사람 목록이 null이면 레코드 생성자(List.copyOf)가 거절한다
        kafka.sendRaw(CHAT_TOPIC, "null-list", GroupMembersInvitedEvent.class.getName(),
                "{\"chatRoomId\":" + room + ",\"inviteRequestId\":1,\"recruitId\":1,\"title\":\"t\","
                        + "\"hostUserId\":1,\"invitedUserIds\":null,\"invitedAt\":\"2026-11-12T01:00:00Z\"}");
        kafka.send(CHAT_TOPIC, "outside", new ChargeCompletedEvent(nextId(), nextId(), 1L, 1L, AFTER_START));

        MemberEventKafka dlt = new MemberEventKafka(bootstrapServers, CHAT_TOPIC);
        assertThat(exceptionClasses(dlt.awaitDeadLetter(CHAT_DLT, "null-list"))).contains("DeserializationException");
        assertThat(dlt.awaitDeadLetter(CHAT_DLT, "outside")).isNotNull();
    }

    private static GroupMembersInvitedEvent invited(long room, long inviteRequestId, List<Long> invitees) {
        return new GroupMembersInvitedEvent(room, inviteRequestId, nextId(), TITLE, nextId(), invitees, AFTER_START);
    }

    private static GroupRoomClosedEvent closed(long room, long host, List<Long> members) {
        return new GroupRoomClosedEvent(room, nextId(), TITLE, host, members, AFTER_START);
    }

    private long member(UserStatus status) {
        long id = nextId();
        save(id, status);
        return id;
    }

    private void save(long id, UserStatus status) {
        users.save(NotificationUser.create(new UserDto(id, "회원", Role.USER, status, 1L)));
    }

    private List<Map<String, Object>> rows(long receiver) {
        return jdbc.queryForList("select * from notification.notification where receiver_user_id = ? order by id",
                receiver);
    }

    private List<String> deadLetterKeysNow() {
        return new MemberEventKafka(bootstrapServers, CHAT_DLT).deadLetterKeysNow(CHAT_DLT);
    }

    private static Instant occurredAt(Map<String, Object> row) {
        return ((Timestamp) row.get("occurred_at")).toInstant();
    }

    /** 표식 폐쇄 알림이 생기면 그 앞의 레코드도 처리가 끝났다(partition 하나) */
    private void awaitProcessed() {
        long room = nextId();
        long marker = member(UserStatus.ACTIVE);
        kafka.send(CHAT_TOPIC, "marker-" + room, closed(room, nextId(), List.of(marker)));
        await().atMost(TIMEOUT).until(() -> rows(marker).size() == 1);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationChatAlertTestTopic() {
            return TopicBuilder.name(CHAT_TOPIC).partitions(1).replicas(1).build();
        }
    }
}
