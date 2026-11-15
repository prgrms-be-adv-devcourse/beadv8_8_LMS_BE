package com.hapbang.auxiliary.notification.in.event.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import com.hapbang.auxiliary.notification.loadtest.B0Category;
import com.hapbang.auxiliary.notification.loadtest.B0Fixture;
import com.hapbang.auxiliary.notification.loadtest.B0FixtureGenerator;
import com.hapbang.auxiliary.notification.loadtest.B0Record;
import com.hapbang.auxiliary.notification.loadtest.B0Topics;

import tools.jackson.databind.json.JsonMapper;

/**
 * B0 고정 이벤트가 알림 소비자의 계약대로 읽히는지 본다(N13, test-spec NT-B0-05, G5).
 * <ul>
 *     <li>강의 토픽 밖의 정상·재전송 레코드는 소비자와 같은 역직렬화기({@code __TypeId__} 헤더, 신뢰 패키지)로 읽혀 같은 레코드가 된다.</li>
 *     <li>강의 토픽은 리스너가 바이트를 직접 읽으므로 JSON으로 읽고, LEGACY 레코드는 이전 형식 판별(ND-086)에서 LEGACY다.</li>
 *     <li>깨진 본문은 역직렬화에 실패한다.</li>
 * </ul>
 */
class B0FixtureContractTest {

    private static final B0Fixture FIXTURE = B0FixtureGenerator.generate(B0FixtureGenerator.DEFAULT_SEED);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> TRUSTED = Map.of(
            B0Topics.MEMBER, NotificationKafkaConstants.MEMBER_EVENTS_TRUSTED_PACKAGE,
            B0Topics.BILLING, NotificationKafkaConstants.BILLING_EVENTS_TRUSTED_PACKAGE,
            B0Topics.APPEARANCE, NotificationKafkaConstants.APPEARANCE_EVENTS_TRUSTED_PACKAGE,
            B0Topics.CUSTOMERSUPPORT, NotificationKafkaConstants.CUSTOMERSUPPORT_EVENTS_TRUSTED_PACKAGE,
            B0Topics.CHAT, NotificationKafkaConstants.CHAT_EVENTS_TRUSTED_PACKAGE);

    @Test
    void 정상_레코드는_소비자_역직렬화기로_읽으면_생성한_레코드와_같다() throws Exception {
        for (B0Record record : FIXTURE.allRecords()) {
            if (record.category() != B0Category.NORMAL && record.category() != B0Category.RESEND) {
                continue;
            }
            Object event = read(record);
            assertThat(event).as(record.line()).isNotInstanceOf(DeserializationException.class);
            assertThat(event).as(record.line()).isEqualTo(record.event());
        }
    }

    @Test
    void LEGACY_레코드는_이전_형식으로_판별된다() throws Exception {
        for (B0Record record : FIXTURE.alerts()) {
            if (record.category() == B0Category.LEGACY) {
                Class<?> type = Class.forName(record.typeId());
                assertThat(ObjectionLegacyFormat.classify(type, record.value().getBytes(StandardCharsets.UTF_8)))
                        .isEqualTo(ObjectionLegacyFormat.LEGACY);
            }
        }
    }

    @Test
    void 깨진_본문은_역직렬화에_실패한다() throws Exception {
        long broken = 0;
        for (B0Record record : FIXTURE.alerts()) {
            if (record.category() == B0Category.BROKEN) {
                RecordHeaders headers = headers(record);
                Object value = deserializer(record.topic()).deserialize(record.topic(), headers,
                        record.value().getBytes(StandardCharsets.UTF_8));
                assertThat(value).isNull();
                // ErrorHandlingDeserializer가 값 역직렬화 예외를 헤더에 담는다(헤더 이름 상수는 제거 예정이라 이름으로 본다)
                assertThat(headers.toArray()).anySatisfy(header ->
                        assertThat(header.key()).endsWith("DeserializerExceptionValue"));
                broken++;
            }
        }
        assertThat(broken).isEqualTo(25);
    }

    private Object read(B0Record record) throws Exception {
        byte[] value = record.value().getBytes(StandardCharsets.UTF_8);
        if (B0Topics.LECTURE.equals(record.topic())) {
            return JSON.readValue(value, Class.forName(record.typeId()));
        }
        return deserializer(record.topic()).deserialize(record.topic(), headers(record), value);
    }

    private static ErrorHandlingDeserializer<Object> deserializer(String topic) {
        return NotificationKafkaConfig.eventDeserializer(TRUSTED.get(topic));
    }

    private static RecordHeaders headers(B0Record record) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("__TypeId__", record.typeId().getBytes(StandardCharsets.UTF_8));
        return headers;
    }
}
