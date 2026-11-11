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
import org.junit.jupiter.api.Test;
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
 * 앱이 뜬 뒤 Backfill을 자동으로 돌리고, 끝날 때까지 출연·지갑 알림 소비자만 시작하지 않는다(ND-078·ND-080).
 * 회원 API 일시 장애 동안은 백오프로 다시 시도하고, 회원 복제본 리스너는 기다리지 않는다. test-spec NT-USR-06·09, NT-GEN-16
 */
@IntegrationTest
@DirtiesContext
@Import({KafkaTestcontainersConfig.class, NotificationAlertListenerStarterTest.FailingSource.class})
class NotificationAlertListenerStarterTest {

    private static final long MEMBER_ID = 950_001L;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(NotificationKafkaConstants.MEMBER_EVENTS_ENABLED, () -> "true");
        registry.add(NotificationKafkaConstants.APPEARANCE_EVENTS_ENABLED, () -> "true");
        registry.add(NotificationKafkaConstants.BILLING_EVENTS_ENABLED, () -> "true");
        registry.add("hapbang.notification.user-backfill.on-startup", () -> "true");
        registry.add("hapbang.notification.user-backfill.retry-initial-interval", () -> "100ms");
        registry.add("hapbang.notification.user-backfill.retry-max-interval", () -> "200ms");
    }

    @Autowired
    private FakeMemberSource source;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private NotificationUserRepository users;

    @Test
    void 회원_API_장애_동안_알림_소비자만_멈추고_회복하면_Backfill을_마친_뒤_시작한다() {
        // 기동 직후부터 계속 실패하는 중: 다시 시도하는 동안 출연·지갑 소비자는 멈춰 있다
        await().atMost(Duration.ofSeconds(30)).until(() -> source.calls() >= 3);
        assertThat(registry.getListenerContainer(NotificationAppearanceEventListener.LISTENER_ID).isRunning())
                .isFalse();
        assertThat(registry.getListenerContainer(NotificationBillingEventListener.LISTENER_ID).isRunning()).isFalse();
        // 회원 복제본 리스너(가입 환영·승급 승인 포함)는 기다리지 않는다
        assertThat(registry.getListenerContainer(NotificationMemberEventListener.LISTENER_ID).isRunning()).isTrue();

        source.failNext(0);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(registry.getListenerContainer(NotificationAppearanceEventListener.LISTENER_ID).isRunning())
                    .isTrue();
            assertThat(registry.getListenerContainer(NotificationBillingEventListener.LISTENER_ID).isRunning())
                    .isTrue();
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
