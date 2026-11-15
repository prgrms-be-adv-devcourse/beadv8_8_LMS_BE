package com.hapbang.customersupport.in;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import com.hapbang.customersupport.fixture.LectureObjectionResults;
import com.hapbang.shared.lecture.event.LectureEventTopic;

/**
 * 강의 앱이 먼저 배포되어 알림용 값(#818)이 붙은 결과를 보내도, 그 값을 모르는 옛 고객센터 앱이 읽을 수 있다(배포 순서 제약 없음).
 * 옛 고객센터 앱의 레코드 모양을 이 테스트 안에 두고, 고객센터 리스너와 같은 역직렬화기(기본 mapper: 모르는 필드 무시)로 읽는다.
 * 강의 앱처럼 지금 레코드를 Kafka JSON 직렬화기로 쓴다
 */
class LectureObjectionResultCompatibilityTest {

    record OldResolved(String commandId, Long inquiryId, Long orderItemId, boolean accepted) {
    }

    record OldWithdrawn(String commandId, Long inquiryId, Long orderItemId) {
    }

    record OldReturnResult(Long inquiryId, Long orderItemId) {
    }

    @Test
    void 옛_모양의_레코드로도_새_필드가_있는_JSON을_읽는다() {
        assertThat(readAs(LectureObjectionResults.resolved(1L, 10L, true), OldResolved.class))
                .isEqualTo(new OldResolved("RESOLVE:1", 1L, 10L, true));
        assertThat(readAs(LectureObjectionResults.withdrawn(1L, 10L), OldWithdrawn.class))
                .isEqualTo(new OldWithdrawn("WITHDRAW:1", 1L, 10L));
        assertThat(readAs(LectureObjectionResults.returnCompleted(1L, 10L), OldReturnResult.class))
                .isEqualTo(new OldReturnResult(1L, 10L));
        assertThat(readAs(LectureObjectionResults.returnActionRequired(1L, 10L), OldReturnResult.class))
                .isEqualTo(new OldReturnResult(1L, 10L));
    }

    private static <T> T readAs(Object event, Class<T> oldType) {
        byte[] json;
        try (JacksonJsonSerializer<Object> serializer = new JacksonJsonSerializer<>()) {
            json = serializer.serialize(LectureEventTopic.NAME, event);
        }
        try (JacksonJsonDeserializer<T> deserializer = new JacksonJsonDeserializer<>(oldType, false)) {
            return deserializer.deserialize(LectureEventTopic.NAME, json);
        }
    }
}
