package com.hapbang.chat.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.hapbang.chat.app.ChatDeliveryUnavailableException;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;
import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatNotifyRule;

import io.lettuce.core.RedisCommandExecutionException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실시간 발행의 Redis 오류 분류: 연결 불가·OOM은 {@link ChatDeliveryUnavailableException}(파이프라인 멈춤, 시도 아님), 그 밖의 명령
 * 오류는 그대로(작업 실패). 전달 큐와 같은 {@link RedisFailures} 분류를 쓴다.
 */
class RedisRealtimeChatFanoutTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final RedisRealtimeChatFanout fanout = new RedisRealtimeChatFanout(template, JsonMapper.builder().build(),
            new ChatRedisKeys("test-fanout"));
    private final ControlFanout control = new ControlFanout(UUID.randomUUID(), ChatControlType.RECHECK_USER, 1L, 1L,
            ChatControlReason.MEMBER_MODIFIED);

    @Test
    void 연결_불가는_전달_인프라_장애로_바꿔_던진다() {
        for (RuntimeException failure : List.of(new RedisConnectionFailureException("Unable to connect"),
                new QueryTimeoutException("Redis command timed out"))) {
            doThrow(failure).when(template).convertAndSend(anyString(), any());

            assertThatThrownBy(() -> fanout.publishControl(control))
                    .isInstanceOf(ChatDeliveryUnavailableException.class)
                    .hasRootCauseMessage(failure.getMessage());
        }
    }

    @Test
    void OOM_거절도_전달_인프라_장애로_다룬다() {
        when(template.convertAndSend(anyString(), any())).thenThrow(new RedisSystemException("OOM",
                new RedisCommandExecutionException("OOM command not allowed when used memory > 'maxmemory'.")));

        assertThatThrownBy(() -> fanout.publishControl(control)).isInstanceOf(ChatDeliveryUnavailableException.class);
    }

    @Test
    void MISCONF_쓰기_거절도_전달_인프라_장애로_다룬다() {
        doThrow(new RedisSystemException("MISCONF", new RedisCommandExecutionException(
                "MISCONF Redis is configured to save RDB snapshots, but it's currently unable to persist to disk.")))
                .when(template).convertAndSend(anyString(), any());

        assertThatThrownBy(() -> fanout.publishControl(control)).isInstanceOf(ChatDeliveryUnavailableException.class);
    }

    @Test
    void 그_밖의_명령_오류는_그대로_던져_작업_실패로_센다() {
        RedisSystemException commandError = new RedisSystemException("ERR",
                new RedisCommandExecutionException("ERR unknown command"));
        when(template.convertAndSend(anyString(), any())).thenThrow(commandError);

        assertThatThrownBy(() -> fanout.publishControl(control)).isSameAs(commandError);
    }

    @Test
    void 구독자_수는_0이어도_그대로_돌려준다() {
        when(template.convertAndSend(anyString(), any())).thenReturn(0L);

        assertThat(fanout.publishControl(control)).isZero();
    }

    @Test
    void 메시지는_서버_채널에_먼저_발행하고_방_채널의_구독자_0명은_성공으로_보고_서버_채널_구독자_수를_돌려준다() {
        ChatRedisKeys keys = new ChatRedisKeys("test-fanout");
        when(template.convertAndSend(eq(keys.activityChannel()), any())).thenReturn(1L);
        when(template.convertAndSend(eq(keys.roomChannel(12)), any())).thenReturn(0L);

        assertThat(fanout.publishMessage(message())).isEqualTo(1);

        var order = inOrder(template);
        order.verify(template).convertAndSend(eq(keys.activityChannel()), any());
        order.verify(template).convertAndSend(eq(keys.roomChannel(12)), any());
    }

    @Test
    void 서버_채널_구독자가_0명이면_자기_구독_단절이라_방_채널은_발행하지_않고_0을_돌려준다() {
        ChatRedisKeys keys = new ChatRedisKeys("test-fanout");
        when(template.convertAndSend(eq(keys.activityChannel()), any())).thenReturn(0L);

        assertThat(fanout.publishMessage(message())).isZero();

        verify(template, never()).convertAndSend(eq(keys.roomChannel(12)), any());
    }

    private static MessageFanout message() {
        ChatMessageResponse response = new ChatMessageResponse(1L, 12L, 3L, 7L, ChatMessageType.TEXT, "안녕", null,
                null, null, null, null, null, null);
        return new MessageFanout(UUID.randomUUID(), MessageFanout.Type.MESSAGE, 12L, 2, response,
                ChatNotifyRule.RECIPIENTS_EXCEPT_SENDER, null);
    }
}
