package com.hapbang.chat.in.event.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import com.hapbang.chat.in.event.ChatMemberEventProperties;

/**
 * 오류 처리기: 재시도 제외 예외는 원인 체인에 있어도 바로 DLT로, 그 밖은 정한 횟수만큼 재시도한 뒤 DLT로 보낸다.
 * 재시도 간격은 시험이 짧게 줄인다(운영 기본값은 1초부터 최대 10초).
 */
class ChatKafkaListenerFactoriesTest {

    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("events.v1", 1, 0L, "1", "{}");
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);

    @Test
    void 재시도_제외_예외는_원인_체인에_있어도_바로_DLT로_보낸다() {
        DefaultErrorHandler handler = handler(3);

        boolean done = handler.handleOne(wrapped(new RejectedEventException("검증 실패", null)), record, consumer,
                container);

        assertThat(done).isTrue();
        verify(template, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 그_밖의_예외는_정한_횟수만큼_재시도한_뒤_그룹별_DLT에_같은_파티션_번호로_보낸다() {
        DefaultErrorHandler handler = handler(2);
        Exception failure = wrapped(new IllegalStateException("일시 실패"));

        assertThat(handler.handleOne(failure, record, consumer, container)).isFalse();
        assertThat(handler.handleOne(failure, record, consumer, container)).isFalse();
        verify(template, never()).send(any(ProducerRecord.class));
        assertThat(handler.handleOne(failure, record, consumer, container)).isTrue();

        org.mockito.ArgumentCaptor<ProducerRecord> sent = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, times(1)).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("events.v1.app.DLT");
        assertThat(sent.getValue().partition()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private DefaultErrorHandler handler(long maxRetries) {
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        // 복구기는 보내기 전에 소비자로 DLT에 같은 번호의 partition이 있는지 확인한다(DLT는 partition 3개)
        when(consumer.partitionsFor(eq("events.v1.app.DLT"), any(Duration.class)))
                .thenReturn(List.of(partition(0), partition(1), partition(2)));
        ChatMemberEventProperties settings = new ChatMemberEventProperties(true, "events.v1", "app", null, maxRetries,
                Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofSeconds(2), null, null);
        return ChatKafkaListenerFactories.errorHandler(template, settings, RejectedEventException.class);
    }

    private static PartitionInfo partition(int number) {
        return new PartitionInfo("events.v1.app.DLT", number, null, null, null);
    }

    private static Exception wrapped(Exception cause) {
        return new ListenerExecutionFailedException("리스너 실패", cause);
    }
}
