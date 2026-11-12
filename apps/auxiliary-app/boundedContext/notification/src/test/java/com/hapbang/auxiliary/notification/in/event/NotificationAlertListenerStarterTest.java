package com.hapbang.auxiliary.notification.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.hapbang.auxiliary.notification.in.event.kafka.NotificationKafkaConstants;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.auxiliary.notification.support.FakeMemberSource;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import java.time.Duration;
import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 앱이 뜬 뒤 Backfill을 자동으로 돌리고, 끝날 때까지 출연·지갑·강의·채팅 알림 소비자만 시작하지 않는다(ND-078·ND-080, 채팅 ND-127).
 * 회원 API 일시 장애 동안은 백오프로 다시 시도하고, 회원 복제본 리스너는 기다리지 않는다. test-spec NT-USR-06·09, NT-GEN-16
 */
@IntegrationTest
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@Import({KafkaTestcontainersConfig.class, NotificationAlertListenerStarterTest.FailingSource.class})
class NotificationAlertListenerStarterTest {

    private static final long MEMBER_ID = 950_001L;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, () -> "true");
        registry.add(NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, () -> "true");
        registry.add(NotificationKafkaConstants.BILLING_EVENTS_ENABLED, () -> "true");
        registry.add(NotificationKafkaConstants.LECTURE_EVENTS_ENABLED, () -> "true");
        registry.add(NotificationKafkaConstants.CHAT_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.user-backfill.on-startup", () -> "true");
        registry.add("hapbang.notification.user-backfill.retry-initial-interval", () -> "100ms");
        registry.add("hapbang.notification.user-backfill.retry-max-interval", () -> "200ms");
        registry.add("hapbang.notification.user-backfill.stall-alert-after", () -> "300ms");
    }

    @Autowired
    private FakeMemberSource source;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private NotificationUserRepository users;

    @Autowired
    private MeterRegistry meters;

    @Test
    void 회원_API_장애_동안_알림_소비자만_멈추고_회복하면_Backfill을_마친_뒤_시작한다(CapturedOutput output) {
        // 기동 직후부터 계속 실패하는 중: 다시 시도하는 동안 출연·지갑·강의·채팅 소비자는 멈춰 있다
        await().atMost(Duration.ofSeconds(30)).until(() -> source.calls() >= 3);
        // 실패가 기준 시간을 넘으면 사람이 봐야 하므로 alert 표식을 붙인 ERROR를 남긴다(docs/dev/logging.md)
        await().atMost(Duration.ofSeconds(30))
                .until(() -> output.getAll().contains("[NOTIFICATION_BACKFILL_STALLED]"));
        assertThat(registry.getListenerContainer(NotificationAppearanceEventListener.LISTENER_ID).isRunning())
                .isFalse();
        assertThat(registry.getListenerContainer(NotificationBillingEventListener.LISTENER_ID).isRunning()).isFalse();
        assertThat(registry.getListenerContainer(NotificationLectureEventListener.LISTENER_ID).isRunning()).isFalse();
        assertThat(registry.getListenerContainer(NotificationChatEventListener.LISTENER_ID).isRunning()).isFalse();
        // 회원 복제본 리스너(가입 환영·승급 승인 포함)는 기다리지 않는다
        assertThat(registry.getListenerContainer(NotificationMemberEventListener.LISTENER_ID).isRunning()).isTrue();
        // 기다리는 동안 이 pod의 대기 게이지는 1이다(ND-093. pod마다 따로 내고 alert는 앱별 max로 본다)
        assertThat(meters.get(NotificationAlertListenerStarter.WAITING_METRIC).gauge().value()).isEqualTo(1.0);

        source.failNext(0);

        // 게이지는 컨테이너를 시작한 뒤에 0이 된다(Starter 스레드). 시작을 본 것만으로 0을 단정하면 느린 환경(CI)에서 경합한다
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(registry.getListenerContainer(NotificationAppearanceEventListener.LISTENER_ID).isRunning())
                    .isTrue();
            assertThat(registry.getListenerContainer(NotificationBillingEventListener.LISTENER_ID).isRunning())
                    .isTrue();
            assertThat(registry.getListenerContainer(NotificationLectureEventListener.LISTENER_ID).isRunning())
                    .isTrue();
            assertThat(registry.getListenerContainer(NotificationChatEventListener.LISTENER_ID).isRunning())
                    .isTrue();
            assertThat(meters.get(NotificationAlertListenerStarter.WAITING_METRIC).gauge().value()).isZero();
        });
        assertThat(users.findById(MEMBER_ID)).isPresent();
    }

    /** 기동 전부터 계속 실패하는 회원 목록 소스 */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingSource {

        @Bean
        @Primary
        FakeMemberSource fakeMemberSource() {
            FakeMemberSource source = new FakeMemberSource();
            source.reset(List.of(new UserDto(MEMBER_ID, "기존회원", Role.USER, UserStatus.ACTIVE, 1L)));
            source.failNext(Integer.MAX_VALUE);
            return source;
        }
    }
}
