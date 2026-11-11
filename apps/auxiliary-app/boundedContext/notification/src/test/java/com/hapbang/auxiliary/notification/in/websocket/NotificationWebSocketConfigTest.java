package com.hapbang.auxiliary.notification.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.util.unit.DataSize;

/**
 * 알림 STOMP 수신·송신 채널 실행기는 설정이 소유하고, 컨텍스트가 닫힐 때(destroy) 종료한다(#829 Bot 리뷰 2차). 빈이 아닌 실행기를
 * 만들어 넘기기만 하면 컨텍스트를 닫아도 스레드가 남는다.
 */
class NotificationWebSocketConfigTest {

    @Test
    void 닫히면_수신_송신_채널_실행기와_heart_beat_스케줄러_스레드가_모두_끝난다() throws Exception {
        Set<Thread> before = configThreads();
        NotificationWebSocketConfig config = config(Duration.ofSeconds(25), 2);
        config.configureClientInboundChannel(new ChannelRegistration());
        config.configureClientOutboundChannel(new ChannelRegistration());
        Set<Thread> created = configThreads();
        created.removeAll(before);
        // 수신 2 + 송신 2 + heart-beat 스케줄러 1(ND-063)
        assertThat(created).hasSize(5);

        config.destroy();

        await().atMost(Duration.ofSeconds(5)).until(() -> created.stream().noneMatch(Thread::isAlive));
    }

    @Test
    void heart_beat가_0_이하이거나_Gateway_stream_idle_5분_이상이면_기동하지_않는다() {
        // ND-068. 실패해도 실행기·스케줄러 스레드를 만들지 않는다
        Set<Thread> before = configThreads();
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(5),
                Duration.ofMinutes(10)}) {
            assertThatThrownBy(() -> config(invalid))
                    .as("heartbeat=%s", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("heartbeat");
        }
        Set<Thread> created = configThreads();
        created.removeAll(before);
        assertThat(created).isEmpty();
    }

    @Test
    void heart_beat가_0_초과_5분_미만이면_기동한다() {
        for (Duration valid : new Duration[] {Duration.ofMillis(1), Duration.ofSeconds(25),
                Duration.ofMinutes(5).minusMillis(1)}) {
            NotificationWebSocketConfig config = config(valid);
            try {
                assertThat(config).isNotNull();
            } finally {
                config.destroy();
            }
        }
    }

    private static NotificationWebSocketConfig config(Duration heartbeat) {
        return config(heartbeat, 1);
    }

    private static NotificationWebSocketConfig config(Duration heartbeat, int channelThreads) {
        return new NotificationWebSocketConfig(mock(NotificationTicketHandshakeInterceptor.class),
                mock(NotificationWebSocketSessions.class), mock(NotificationStompInterceptor.class),
                new String[] {"http://localhost:5173"}, Duration.ofSeconds(10), DataSize.ofKilobytes(512),
                channelThreads, channelThreads, heartbeat);
    }

    /**
     * 이 설정이 만드는 스레드(수신·송신 실행기, heart-beat 스케줄러)만 센다. 같은 `notification-ws-` 접두사를 쓰는 연결 만료
     * 스케줄러(`notification-ws-expiry-`, 캐시된 다른 시험 컨텍스트)가 섞이면 스냅샷 비교가 거짓으로 실패한다
     */
    private static Set<Thread> configThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> CONFIG_THREAD_PREFIXES.stream().anyMatch(thread.getName()::startsWith))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static final List<String> CONFIG_THREAD_PREFIXES =
            List.of("notification-ws-in-", "notification-ws-out-", "notification-ws-heartbeat-");
}
