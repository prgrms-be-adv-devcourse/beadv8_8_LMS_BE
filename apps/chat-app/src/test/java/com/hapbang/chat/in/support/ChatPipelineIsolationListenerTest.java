package com.hapbang.chat.in.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestContext;

import com.hapbang.chat.in.redis.ChatRealtimeRedisListener;
import com.hapbang.chat.in.stream.ChatDeliveryPipelineRunner;
import com.hapbang.chat.out.redis.ChatRedisKeys;

/**
 * 메서드 사이 격리: 파이프라인을 멈춘 뒤 worker·주기 작업이 모두 끝났음을 확인한 다음에만 Stream·DB를 정리한다. 끝나지 않으면
 * 정리하지 않고(DB 정리 리스너도 건너뛴다) 컨텍스트를 닫으며 테스트를 실패시킨다.
 */
class ChatPipelineIsolationListenerTest {

    private final TestContext testContext = mock(TestContext.class);
    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private final ChatDeliveryPipelineRunner runner = mock(ChatDeliveryPipelineRunner.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ChatRedisKeys keys = new ChatRedisKeys("test-isolation");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(testContext.getApplicationContext()).thenReturn(applicationContext);
        ObjectProvider<ChatDeliveryPipelineRunner> runnerProvider = mock(ObjectProvider.class);
        when(runnerProvider.getIfAvailable()).thenReturn(runner);
        ObjectProvider<ChatRealtimeRedisListener> listenerProvider = mock(ObjectProvider.class);
        when(applicationContext.getBeanProvider(ChatDeliveryPipelineRunner.class)).thenReturn(runnerProvider);
        when(applicationContext.getBeanProvider(ChatRealtimeRedisListener.class)).thenReturn(listenerProvider);
        when(applicationContext.getBean(ChatRedisKeys.class)).thenReturn(keys);
        when(applicationContext.getBean(StringRedisTemplate.class)).thenReturn(redis);
    }

    @Test
    void 파이프라인_작업이_끝나지_않으면_Stream과_DB를_정리하지_않고_컨텍스트를_닫으며_실패한다() {
        when(runner.isFullyStopped()).thenReturn(false);
        ChatPipelineIsolationListener listener = new ChatPipelineIsolationListener(Duration.ofMillis(200));

        assertThatThrownBy(() -> listener.afterTestMethod(testContext)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정리하지 않습니다");

        verify(runner).stop();
        verify(redis, never()).delete(anyCollection());
        verify(testContext).setAttribute(ChatPipelineIsolationListener.PIPELINE_STILL_RUNNING, true);
        verify(testContext).markApplicationContextDirty(null);
    }

    @Test
    void 파이프라인_작업이_모두_끝났으면_이_namespace의_Stream만_지운다() {
        when(runner.isFullyStopped()).thenReturn(false, true);
        ChatPipelineIsolationListener listener = new ChatPipelineIsolationListener(Duration.ofSeconds(5));

        listener.afterTestMethod(testContext);

        verify(redis).delete(List.of(keys.deliveryStream(), keys.controlStream()));
        verify(testContext, never()).setAttribute(eq(ChatPipelineIsolationListener.PIPELINE_STILL_RUNNING), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 구독자_처리_작업이_끝나지_않으면_Stream과_DB를_정리하지_않고_컨텍스트를_닫으며_실패한다() {
        when(runner.isFullyStopped()).thenReturn(true);
        ChatRealtimeRedisListener realtimeListener = mock(ChatRealtimeRedisListener.class);
        when(realtimeListener.awaitIdle(any())).thenReturn(false);
        ObjectProvider<ChatRealtimeRedisListener> listenerProvider = mock(ObjectProvider.class);
        when(listenerProvider.getIfAvailable()).thenReturn(realtimeListener);
        when(applicationContext.getBeanProvider(ChatRealtimeRedisListener.class)).thenReturn(listenerProvider);
        ChatPipelineIsolationListener listener = new ChatPipelineIsolationListener(Duration.ofSeconds(1),
                Duration.ofMillis(100));

        assertThatThrownBy(() -> listener.afterTestMethod(testContext)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정리하지 않습니다");

        verify(realtimeListener).pause();
        verify(redis, never()).delete(anyCollection());
        verify(testContext).setAttribute(ChatPipelineIsolationListener.PIPELINE_STILL_RUNNING, true);
        verify(testContext).markApplicationContextDirty(null);
    }

    @Test
    void 파이프라인_작업이_살아_있다고_표시되면_DB_정리를_건너뛴다() {
        when(testContext.getAttribute(ChatPipelineIsolationListener.PIPELINE_STILL_RUNNING)).thenReturn(true);
        when(testContext.getTestMethod()).thenReturn(getClass().getDeclaredMethods()[0]);

        new ChatE2EDatabaseCleanUpListener().afterTestMethod(testContext);

        verify(testContext, never()).getApplicationContext();
    }
}
