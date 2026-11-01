package com.hapbang.chat.in;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import com.hapbang.chat.in.support.ChatE2ETest;
import com.hapbang.chat.in.websocket.ChatInboundReceiveBudget;
import com.hapbang.chat.in.websocket.ChatOutboundSendBudget;
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
}
