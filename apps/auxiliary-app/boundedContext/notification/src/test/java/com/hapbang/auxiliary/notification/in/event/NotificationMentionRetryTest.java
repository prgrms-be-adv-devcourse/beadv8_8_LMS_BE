package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConfig;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConsumerSettings;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;

/**
 * 문구에 넣을 상대 회원 복제본이 없으면 재시도할 수 있는 오류로 실패시킨다(ND-091). 첫 처리에서 실패한 것을 확인한 뒤 복제본을 넣으면
 * 블로킹 재시도에서 알림이 만들어지고 DLT로 가지 않는다. 재시도 간격을 2초로 늘려 그 사이에 복제본을 넣는다.
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationMentionRetryTest.Topics.class})
class NotificationMentionRetryTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "appearance.events.v1.mention-test-" + SUFFIX;
    private static final String GROUP = "notification-mention-test-" + SUFFIX;
    private static final String DLT = TOPIC + "." + GROUP + ".DLT";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, () -> "true");
    }

    @TestBean(name = NotificationKafkaConfig.APPEARANCE_EVENT_SETTINGS)
    private NotificationKafkaConsumerSettings appearanceEventSettings;

    static NotificationKafkaConsumerSettings appearanceEventSettings() {
        return new NotificationKafkaConsumerSettings(TOPIC, GROUP, DLT, 3, Duration.ofSeconds(2),
                Duration.ofSeconds(2), NotificationKafkaConstants.DEAD_LETTER_SEND_TIMEOUT, 1,
                NotificationKafkaConstants.DEAD_LETTER_REPLICAS);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @MockitoSpyBean
    private NotificationUserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 상대_회원_복제본이_없으면_재시도하고_그_사이_복제본이_생기면_DLT_없이_만든다() {
        long host = 980_001L;
        long guest = 980_002L;
        users.save(NotificationUser.create(new UserDto(guest, "게스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L)));

        try (AlertEventKafka kafka = new AlertEventKafka(bootstrapServers)) {
            kafka.send(TOPIC, "appearance-request:retry", new AppearanceRequestSentEvent(new AppearanceRequestDto(
                    980_100L, host, guest, "재시도", 1L, LocalDateTime.parse("2026-11-20T20:00:00"),
                    LocalDateTime.parse("2026-11-13T18:30:00"))), Instant.parse("2026-11-11T03:00:00Z"));
        }
        // 첫 처리에서 호스트 복제본을 찾았고(없어 실패), 재시도 전에 복제본을 넣는다
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> verify(users, atLeastOnce()).findById(eq(host)));
        users.save(NotificationUser.create(new UserDto(host, "늦은호스트", Role.INFLUENCER, UserStatus.ACTIVE, 1L)));

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "select count(*) from notification.notification where receiver_user_id = ?", Long.class, guest) == 1);
        assertThat(jdbc.queryForObject("select message from notification.notification where receiver_user_id = ?",
                String.class, guest)).startsWith("늦은호스트님이");
        assertThat(new MemberEventKafka(bootstrapServers, DLT).deadLetterKeysNow(DLT))
                .doesNotContain("appearance-request:retry");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic notificationMentionRetryTestTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }
    }
}
