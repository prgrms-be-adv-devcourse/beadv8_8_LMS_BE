package com.hapbang.chat.in;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.ImmutableMessageChannelInterceptor;

import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.websocket.ChatInboundReceiveBudget;
import com.hapbang.chat.in.websocket.ChatOutboundSendBudget;
import com.hapbang.chat.in.websocket.ChatStompAuthInterceptor;
import com.hapbang.chat.in.websocket.ChatStompInterceptor;

/**
 * 실제 STOMP 설정의 앱 전체 송신 채널(clientOutboundChannel)이 상한 있는 송신 예산 실행기를 쓰는지 확인한다.
 * Boot의 configurer가 넣는 공용 실행기에 가려지지 않아야 한다. 넘침 동작은 {@code ChatOutboundSendBudgetTest}가 검증한다.
 */
@ChatE2ETest
class ChatWebSocketTransportE2ETest {

    @Autowired
    @Qualifier("clientOutboundChannel")
    private ExecutorSubscribableChannel clientOutboundChannel;

    @Autowired
    private ChatOutboundSendBudget chatOutboundSendBudget;

    @Autowired
    @Qualifier("clientInboundChannel")
    private ExecutorSubscribableChannel clientInboundChannel;

    @Autowired
    private ChatInboundReceiveBudget chatInboundReceiveBudget;

    @Test
    void 송신_채널은_스레드와_대기열에_상한이_있는_송신_예산_실행기를_쓴다() {
        assertThat(clientOutboundChannel.getExecutor()).isSameAs(chatOutboundSendBudget.executor());
        // 코드 기본값(미확정 초기값 Q12). 테스트는 앱 YAML을 읽지 않는다
        assertThat(chatOutboundSendBudget.executor().getCorePoolSize()).isEqualTo(16);
        assertThat(chatOutboundSendBudget.executor().getMaxPoolSize()).isEqualTo(16);
        assertThat(chatOutboundSendBudget.executor().getQueueCapacity()).isEqualTo(10_000);
    }

    @Test
    void 수신_채널은_공용_taskScheduler가_아니라_상한이_있는_수신_예산_실행기를_쓰고_인증_검사를_유지한다() {
        assertThat(clientInboundChannel.getExecutor()).isSameAs(chatInboundReceiveBudget.channelExecutor());
        assertThat(chatInboundReceiveBudget.executor().getCorePoolSize()).isEqualTo(8);
        assertThat(chatInboundReceiveBudget.executor().getMaxPoolSize()).isEqualTo(8);
        assertThat(chatInboundReceiveBudget.executor().getQueueCapacity()).isEqualTo(1_000);
        assertThat(clientInboundChannel.getInterceptors()).hasAtLeastOneElementOfType(ChatStompInterceptor.class);
    }

    // 인증(회원 결합·만료 예약)이 회원을 쓰는 자격·경로 검사보다 먼저 돌아야 한다(설계 문서 6장). 둘 다 Spring의 불변 메시지
    // 인터셉터보다 앞이라 accessor.setUser가 반영된다
    @Test
    void 수신_채널의_인터셉터는_인증_다음에_자격_경로_검사_순서다() {
        List<ChannelInterceptor> interceptors = clientInboundChannel.getInterceptors();
        int auth = indexOf(interceptors, ChatStompAuthInterceptor.class);
        int chat = indexOf(interceptors, ChatStompInterceptor.class);
        int immutable = indexOf(interceptors, ImmutableMessageChannelInterceptor.class);

        assertThat(auth).isNotNegative().isLessThan(chat);
        assertThat(chat).isLessThan(immutable);
    }

    private static int indexOf(List<ChannelInterceptor> interceptors, Class<?> type) {
        for (int i = 0; i < interceptors.size(); i++) {
            if (type.isInstance(interceptors.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
