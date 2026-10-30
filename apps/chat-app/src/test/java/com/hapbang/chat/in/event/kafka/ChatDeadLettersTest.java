package com.hapbang.chat.in.event.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.mock.MockProducerFactory;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.SendResult;

import com.hapbang.chat.in.event.ChatMemberEventProperties;
import com.hapbang.chat.in.event.kafka.fixture.event.SampleEvent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ChatDeadLettersTest {

    private static final String DLT = "events.v1.app.DLT";
    private static final ChatMemberEventProperties SETTINGS = settings(Duration.ofSeconds(2));

    @Test
    void 발행_대기_시간을_메타데이터_대기와_전송_완료_대기로_나누고_원래_설정은_바꾸지_않는다() {
        Map<String, Object> base = Map.of(ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.LINGER_MS_CONFIG, 5);

        Map<String, Object> properties = ChatDeadLetters.producerProperties(base, Duration.ofSeconds(10));

        assertThat(properties)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000L)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000)
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 0);
        assertThat(base).containsEntry(ProducerConfig.LINGER_MS_CONFIG, 5).hasSize(2);
        // 홀수 밀리초도 두 몫의 합이 전체와 같다
        Duration odd = Duration.ofMillis(1_001);
        assertThat(ChatDeadLetters.maxBlockMillis(odd) + ChatDeadLetters.deliveryMillis(odd)).isEqualTo(1_001);
    }

    @Test
    void 발행_대기_시간이_1초보다_짧으면_받지_않는다() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ChatDeadLetters.producerProperties(Map.of(), Duration.ofMillis(999)));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatDeadLetters.maxBlockMillis(null));
    }

    @Test
    void 읽지_못한_원본은_바이트_그대로_읽은_레코드는_JSON과_타입_헤더_하나로_직렬화한다() {
        Serializer<Object> serializer = ChatDeadLetters.valueSerializer();
        byte[] raw = "{not-json".getBytes(StandardCharsets.UTF_8);
        Headers rawHeaders = new RecordHeaders();

        assertThat(serializer.serialize(DLT, rawHeaders, raw)).isEqualTo(raw);
        assertThat(rawHeaders.lastHeader("__TypeId__")).isNull();

        Headers headers = new RecordHeaders();
        headers.add("__TypeId__", SampleEvent.class.getName().getBytes(StandardCharsets.UTF_8));
        byte[] json = serializer.serialize(DLT, headers, new SampleEvent(1L, "a"));

        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo("{\"id\":1,\"name\":\"a\"}");
        List<String> typeIds = new ArrayList<>();
        headers.headers("__TypeId__").forEach(header -> typeIds.add(new String(header.value(), StandardCharsets.UTF_8)));
        assertThat(typeIds).containsExactly(SampleEvent.class.getName());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 복구기는_그룹별_DLT에_원본과_같은_파티션_번호로_보내며_발행이_실패하면_예외를_던진다() {
        KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        DeadLetterPublishingRecoverer recoverer = ChatDeadLetters.recoverer(template, SETTINGS);

        assertThatThrownBy(() -> recoverer.accept(record(), new IllegalStateException("처리 실패")))
                .isInstanceOf(KafkaException.class);

        ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo(DLT);
        assertThat(sent.getValue().partition()).isEqualTo(0);
        assertThat(sent.getValue().key()).isEqualTo("7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 복구기의_결과_대기는_전송_완료_대기_몫을_넘지_않는다() {
        Duration sendTimeout = Duration.ofSeconds(1);
        KafkaOperations<Object, Object> template = mock(KafkaOperations.class);
        ProducerFactory<Object, Object> producerFactory = mock(ProducerFactory.class);
        when(producerFactory.getConfigurationProperties())
                .thenReturn(ChatDeadLetters.producerProperties(Map.of(), sendTimeout));
        when(template.getProducerFactory()).thenReturn(producerFactory);
        when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<SendResult<Object, Object>>());
        DeadLetterPublishingRecoverer recoverer = ChatDeadLetters.recoverer(template, settings(sendTimeout));

        long start = System.nanoTime();
        assertThatThrownBy(() -> recoverer.accept(record(), new IllegalStateException("처리 실패")))
                .isInstanceOf(KafkaException.class);

        // spring-kafka 기본 여유(5초)를 더하지 않아 전송 완료 대기 몫(0.5초) 근처에서 끝난다
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(sendTimeout);
    }

    @Test
    void DLT_토픽은_그룹별_이름에_원본과_같은_파티션_3개와_7일_보존이다() {
        assertThat(ChatDeadLetters.topicName("member.events.v1", "chat-app.member-replica"))
                .isEqualTo("member.events.v1.chat-app.member-replica.DLT");
        assertThat(ChatDeadLetters.newTopic(settings(Duration.ofSeconds(10)))).satisfies(topic -> {
            assertThat(topic.name()).isEqualTo(DLT);
            assertThat(topic.numPartitions()).isEqualTo(3);
            assertThat(topic.configs()).containsEntry("retention.ms", "604800000");
        });
    }

    private static ChatMemberEventProperties settings(Duration sendTimeout) {
        return new ChatMemberEventProperties(true, "events.v1", "app", null, null, null, null, sendTimeout, null, null);
    }

    private static ConsumerRecord<String, byte[]> record() {
        return new ConsumerRecord<>("events.v1", 0, 3L, "7", "{}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void DLT_템플릿은_비동기_발행_실패_로그에_key와_값을_남기지_않는다() {
        List<ILoggingEvent> logs = sendAndFailAsync(ChatDeadLetters::template);

        assertThat(logs).isNotEmpty();
        assertThat(logs).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .contains(DLT).doesNotContain("secret-value").doesNotContain("member-key"));
    }

    @Test
    void 비교_기본_템플릿은_같은_실패에서_값을_로그에_남긴다() {
        List<ILoggingEvent> logs = sendAndFailAsync(KafkaTemplate::new);

        assertThat(logs).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("secret-value"));
    }

    private static List<ILoggingEvent> sendAndFailAsync(
            Function<MockProducerFactory<String, String>, KafkaTemplate<String, String>> templates) {
        MockProducer<String, String> producer = new MockProducer<>(false, null, new StringSerializer(),
                new StringSerializer());
        KafkaTemplate<String, String> template = templates.apply(new MockProducerFactory<>(() -> producer));
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingProducerListener.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CompletableFuture<?> sent = template.send(DLT, "member-key", "secret-value");
            // 브로커 응답 대기 중 시간 초과 같은 비동기 실패
            producer.errorNext(new TimeoutException("DLT 발행 시간 초과(테스트)"));
            assertThat(sent).isCompletedExceptionally();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
