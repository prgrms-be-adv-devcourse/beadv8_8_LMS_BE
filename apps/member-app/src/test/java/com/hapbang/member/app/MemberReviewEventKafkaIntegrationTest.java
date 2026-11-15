package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.InfluencerUpgradeRequestRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.InfluencerChannelApprovedEvent;
import com.hapbang.shared.member.event.InfluencerChannelRejectedEvent;
import com.hapbang.shared.member.event.InfluencerUpgradeRequestRejectedEvent;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 채널 인증·승급 심사 이벤트 3종을 Kafka {@code member.events.v1}로 외부화한다 (#874, 알림 계약 M6·ND-042).
 * 다른 통합 테스트도 같은 토픽에 보내고, 승인은 같은 key로 {@code UserModifiedEvent}도 나가므로 테스트마다 고유한 소비자 그룹으로
 * 처음부터 읽고 이 테스트의 회원 ID(key)와 {@code eventType}으로 거른다 ({@link MemberLogoutEventKafkaIntegrationTest}와 같은 방식)
 */
@MemberIntegrationTest
@WithMockUser(roles = "ADMIN")
class MemberReviewEventKafkaIntegrationTest {

    private static final String TOPIC = "member.events.v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Long ADMIN_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 10, 0);
    private static final AtomicInteger CHANNEL_SEQUENCE = new AtomicInteger();

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    MemberChannelReviewFacade memberChannelReviewFacade;

    @Autowired
    MemberUpgradeReviewFacade memberUpgradeReviewFacade;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Autowired
    InfluencerUpgradeRequestRepository influencerUpgradeRequestRepository;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private String group;
    private Consumer<String, byte[]> consumer;

    @BeforeEach
    void subscribe() {
        group = "member-review-event-test-" + UUID.randomUUID();
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                new StringDeserializer(), new ByteArrayDeserializer());
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterEach
    void close() throws Exception {
        consumer.close();
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // poll 전에 끝난 테스트는 그룹이 만들어지지 않는다
            if (!(e.getCause() instanceof GroupIdNotFoundException)) {
                throw e;
            }
        }
    }

    @Test
    @DisplayName("관리자가 채널을 승인하면 회원 ID를 key로 회차·승인 방식·승인 시각을 담은 InfluencerChannelApprovedEvent가 발행된다")
    void channelApproved() {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel channel = pendingChannel(user);

        memberChannelReviewFacade.approve(ADMIN_ID, channel.getId());

        ConsumerRecord<String, byte[]> record = awaitRecord(user.getId(), InfluencerChannelApprovedEvent.class);
        assertContract(record, InfluencerChannelApprovedEvent.class,
                "userId", "channelId", "reviewSequence", "autoApproved", "approvedAt");
        InfluencerChannelApprovedEvent event = jsonMapper.readValue(record.value(), InfluencerChannelApprovedEvent.class);
        assertThat(event.userId()).isEqualTo(user.getId());
        assertThat(event.channelId()).isEqualTo(channel.getId());
        assertThat(event.reviewSequence()).isEqualTo(1);
        assertThat(event.autoApproved()).isFalse();
        assertThat(event.approvedAt()).isNotNull();
    }

    @Test
    @DisplayName("관리자가 채널을 반려하면 회원 ID를 key로 회차·사유·반려 시각을 담은 InfluencerChannelRejectedEvent가 발행된다")
    void channelRejected() {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel channel = pendingChannel(user);

        memberChannelReviewFacade.reject(ADMIN_ID, channel.getId(), "방송 시간 부족");

        ConsumerRecord<String, byte[]> record = awaitRecord(user.getId(), InfluencerChannelRejectedEvent.class);
        assertContract(record, InfluencerChannelRejectedEvent.class,
                "userId", "channelId", "reviewSequence", "rejectionReason", "rejectedAt");
        InfluencerChannelRejectedEvent event = jsonMapper.readValue(record.value(), InfluencerChannelRejectedEvent.class);
        assertThat(event.userId()).isEqualTo(user.getId());
        assertThat(event.channelId()).isEqualTo(channel.getId());
        assertThat(event.reviewSequence()).isEqualTo(1);
        assertThat(event.rejectionReason()).isEqualTo("방송 시간 부족");
        assertThat(event.rejectedAt()).isNotNull();
    }

    @Test
    @DisplayName("관리자가 승급 신청을 반려하면 회원 ID를 key로 신청 ID·사유·반려 시각을 담은 InfluencerUpgradeRequestRejectedEvent가 발행된다")
    void upgradeRequestRejected() {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest request = influencerUpgradeRequestRepository.save(
                InfluencerUpgradeRequest.request(influencer, "강의 경력 3년", NOW));

        memberUpgradeReviewFacade.reject(ADMIN_ID, request.getId(), "활동 내역 부족");

        ConsumerRecord<String, byte[]> record = awaitRecord(influencer.getId(),
                InfluencerUpgradeRequestRejectedEvent.class);
        assertContract(record, InfluencerUpgradeRequestRejectedEvent.class,
                "userId", "upgradeRequestId", "rejectionReason", "rejectedAt");
        InfluencerUpgradeRequestRejectedEvent event =
                jsonMapper.readValue(record.value(), InfluencerUpgradeRequestRejectedEvent.class);
        assertThat(event.userId()).isEqualTo(influencer.getId());
        assertThat(event.upgradeRequestId()).isEqualTo(request.getId());
        assertThat(event.rejectionReason()).isEqualTo("활동 내역 부족");
        assertThat(event.rejectedAt()).isNotNull();
    }

    /** 헤더(타입·이름·버전)와 JSON 필드 이름이 계약 그대로인지 본다. 필드가 더 붙거나 이름이 바뀌면 실패한다 */
    private void assertContract(ConsumerRecord<String, byte[]> record, Class<?> type, String... fields) {
        assertThat(headers(record)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "__TypeId__", type.getName(),
                "eventType", type.getSimpleName(),
                "eventVersion", "1"));
        Map<String, Object> body = jsonMapper.readValue(record.value(), new TypeReference<>() {
        });
        assertThat(body).containsOnlyKeys(fields);
    }

    /** 이 회원 ID를 key로 가진 그 종류의 레코드를 하나 받을 때까지 읽는다 */
    private ConsumerRecord<String, byte[]> awaitRecord(Long userId, Class<?> type) {
        List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(250)).records(TOPIC).forEach(record -> {
                Header eventType = record.headers().lastHeader("eventType");
                if (userId.toString().equals(record.key()) && eventType != null
                        && type.getSimpleName().equals(new String(eventType.value(), StandardCharsets.UTF_8))) {
                    received.add(record);
                }
            });
            assertThat(received).isNotEmpty();
        });
        return received.getFirst();
    }

    private static Map<String, String> headers(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header header : record.headers()) {
            // trace 헤더는 발행 계약이 아니라 trace 계측(#630)이 붙인다
            if (header.key().equals("traceparent")) {
                continue;
            }
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    /** 팔로워 미달로 승인 요청해 심사 대기가 된 채널 (심사 회차 1) */
    private InfluencerChannel pendingChannel(User user) {
        InfluencerChannel channel = InfluencerChannel.register(user, ChannelPlatform.CHZZK,
                "review-event-channel-" + CHANNEL_SEQUENCE.incrementAndGet(), "테스트 채널", 5, NOW);
        channel.requestApproval(5, NOW);
        return influencerChannelRepository.save(channel);
    }
}
