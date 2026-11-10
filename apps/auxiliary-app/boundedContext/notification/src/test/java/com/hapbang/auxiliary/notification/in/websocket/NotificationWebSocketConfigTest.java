package com.hapbang.auxiliary.notification.in.websocket;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.HashSet;
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
    void 닫히면_수신_송신_채널_실행기_스레드가_모두_끝난다() throws Exception {
        // 같은 JVM의 다른 시험 컨텍스트가 만든 스레드와 섞이지 않게, 이 설정이 만든 스레드만 본다
        Set<Thread> before = threads("notification-ws-");
        NotificationWebSocketConfig config = new NotificationWebSocketConfig(
                mock(NotificationTicketHandshakeInterceptor.class), mock(NotificationWebSocketSessions.class),
                mock(NotificationStompInterceptor.class), new String[] {"http://localhost:5173"}, Duration.ofSeconds(10),
                DataSize.ofKilobytes(512), 2, 2);
        config.configureClientInboundChannel(new ChannelRegistration());
        config.configureClientOutboundChannel(new ChannelRegistration());
        Set<Thread> created = threads("notification-ws-");
        created.removeAll(before);
        assertThat(created).hasSize(4);

        config.destroy();

        await().atMost(Duration.ofSeconds(5)).until(() -> created.stream().noneMatch(Thread::isAlive));
    }

    private static Set<Thread> threads(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith(prefix))
                .collect(Collectors.toCollection(HashSet::new));
    }
}
