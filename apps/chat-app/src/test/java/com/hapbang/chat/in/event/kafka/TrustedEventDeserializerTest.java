package com.hapbang.chat.in.event.kafka;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import com.hapbang.chat.in.event.kafka.fixture.OutsideEvent;
import com.hapbang.chat.in.event.kafka.fixture.event.SampleEvent;

class TrustedEventDeserializerTest {

    private static final String TRUSTED = "com.hapbang.chat.in.event.kafka.fixture.event";
    private static final String TYPE_ID = "__TypeId__";

    private final ErrorHandlingDeserializer<Object> deserializer = ChatKafkaConfig.eventDeserializer(TRUSTED);

    @Test
    void 신뢰_패키지의_타입_헤더로_레코드를_되돌리고_타입_헤더를_남긴다() {
        Headers headers = headers(SampleEvent.class.getName());

        Object value = deserializer.deserialize("t", headers, bytes("{\"id\":1,\"name\":\"a\"}"));

        assertThat(value).isEqualTo(new SampleEvent(1L, "a"));
        assertThat(typeIds(headers)).containsExactly(SampleEvent.class.getName());
    }

    @Test
    void 본문을_읽지_못하면_null과_예외_헤더를_남기고_타입_헤더는_지우지_않는다() {
        Headers headers = headers(SampleEvent.class.getName());

        Object value = deserializer.deserialize("t", headers, bytes("{not-json"));

        assertThat(value).isNull();
        assertThat(headers.lastHeader(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
        assertThat(typeIds(headers)).containsExactly(SampleEvent.class.getName());
    }

    @Test
    void 상위_패키지의_타입은_신뢰하지_않는다() {
        Headers headers = headers(OutsideEvent.class.getName());

        Object value = deserializer.deserialize("t", headers, bytes("{\"id\":1}"));

        assertThat(value).isNull();
        assertThat(headers.lastHeader(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
    }

    @Test
    void 신뢰_패키지_바로_안에서_클래스가_없는_타입을_본문을_읽지_않고_표시_값으로_돌려준다() {
        ErrorHandlingDeserializer<Object> skipping = deserializer;
        Headers headers = headers(TRUSTED + ".NotYetDeployedEvent");

        // 본문이 깨져 있어도 읽지 않는다(모르는 종류의 본문은 받는 쪽 계약이 아니다)
        Object value = skipping.deserialize("t", headers, bytes("{not-json"));

        assertThat(value).isEqualTo(new MissingTypeEvent(TRUSTED + ".NotYetDeployedEvent"));
        assertThat(headers.lastHeader(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNull();
        assertThat(typeIds(headers)).containsExactly(TRUSTED + ".NotYetDeployedEvent");
    }

    @Test
    void 있는_클래스는_그대로_읽고_나머지_실패는_역직렬화_실패로_남긴다() {
        ErrorHandlingDeserializer<Object> skipping = deserializer;

        assertThat(skipping.deserialize("t", headers(SampleEvent.class.getName()), bytes("{\"id\":1,\"name\":\"a\"}")))
                .isEqualTo(new SampleEvent(1L, "a"));
        // 클래스는 있는데 본문을 읽지 못함
        assertFailed(skipping, headers(SampleEvent.class.getName()), "{not-json");
        // 신뢰 패키지 밖(상위 패키지)의 있는 타입과 없는 타입
        assertFailed(skipping, headers(OutsideEvent.class.getName()), "{\"id\":1}");
        assertFailed(skipping, headers("com.hapbang.chat.in.event.kafka.fixture.NotYetDeployedEvent"), "{\"id\":1}");
        // 신뢰 패키지의 하위 패키지는 신뢰 범위가 아니다
        assertFailed(skipping, headers(TRUSTED + ".sub.NotYetDeployedEvent"), "{\"id\":1}");
        // 타입 헤더 없음
        assertFailed(skipping, new RecordHeaders(), "{\"id\":1}");
        // 값 없음은 표시 값이 아니라 null이고 실패도 아니다
        Headers tombstone = headers(TRUSTED + ".NotYetDeployedEvent");
        assertThat(skipping.deserialize("t", tombstone, (byte[]) null)).isNull();
        assertThat(tombstone.lastHeader(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNull();
    }

    @Test
    void 바이트가_있는데_읽은_결과가_null이면_tombstone과_구분해_역직렬화_실패로_남긴다() {
        ErrorHandlingDeserializer<Object> skipping = deserializer;

        assertFailed(skipping, headers("java.lang.Void"), "{\"a\":1}");
        assertFailed(skipping, headers("java.lang.String"), "null");
    }

    @Test
    void 타입_헤더가_없으면_읽지_않는다() {
        Headers headers = new RecordHeaders();

        assertThat(deserializer.deserialize("t", headers, bytes("{\"id\":1,\"name\":\"a\"}"))).isNull();
        assertThat(headers.lastHeader(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
    }

    @Test
    void 와일드카드나_빈_신뢰_패키지는_받지_않는다() {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatKafkaConfig.eventDeserializer("*"));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatKafkaConfig.eventDeserializer("com.hapbang.*"));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatKafkaConfig.eventDeserializer(" "));
    }

    private static void assertFailed(ErrorHandlingDeserializer<Object> deserializer, Headers headers, String body) {
        assertThat(deserializer.deserialize("t", headers, bytes(body))).isNull();
        assertThat(headers.lastHeader(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
    }

    private static Headers headers(String typeId) {
        Headers headers = new RecordHeaders();
        headers.add(TYPE_ID, typeId.getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    private static List<String> typeIds(Headers headers) {
        List<String> values = new ArrayList<>();
        headers.headers(TYPE_ID).forEach(header -> values.add(new String(header.value(), StandardCharsets.UTF_8)));
        return values;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
