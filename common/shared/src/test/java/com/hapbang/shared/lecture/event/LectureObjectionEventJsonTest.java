package com.hapbang.shared.lecture.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * 이의 제기 결과 이벤트 4종의 JSON 호환 (#818). 배포 전에 저장된 발행 기록(outbox)을 다시 보낼 때와, 고객센터가 옛 형식을 받을 때
 * 새 필드가 없는 JSON을 읽는다. 기본 {@link JsonMapper}(Jackson 3 기본값: 빠진 primitive는 실패)로 읽어 가장 엄격한 경우를 본다.
 */
class LectureObjectionEventJsonTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 11, 10, 10, 30, 0, 0, KST);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 새_필드가_없는_옛_JSON을_읽으면_새_필드는_null이다() {
        assertThat(read("""
                {"commandId":"RESOLVE:1","inquiryId":1,"orderItemId":10,"accepted":true}
                """, LectureObjectionResolvedEvent.class))
                .isEqualTo(new LectureObjectionResolvedEvent("RESOLVE:1", 1L, 10L, true, null, null, null, null));
        assertThat(read("""
                {"commandId":"WITHDRAW:1","inquiryId":1,"orderItemId":10}
                """, LectureObjectionWithdrawnEvent.class))
                .isEqualTo(new LectureObjectionWithdrawnEvent("WITHDRAW:1", 1L, 10L, null, null, null, null));
        assertThat(read("""
                {"inquiryId":1,"orderItemId":10}
                """, LectureObjectionReturnCompletedEvent.class))
                .isEqualTo(new LectureObjectionReturnCompletedEvent(1L, 10L, null, null, null, null));
        assertThat(read("""
                {"inquiryId":1,"orderItemId":10}
                """, LectureObjectionReturnActionRequiredEvent.class))
                .isEqualTo(new LectureObjectionReturnActionRequiredEvent(1L, 10L, null, null, null, null, null));
    }

    @Test
    void 새_필드를_JSON으로_썼다가_읽어도_같고_시각은_KST_offset으로_나간다() {
        LectureObjectionResolvedEvent resolved = new LectureObjectionResolvedEvent("RESOLVE:1", 1L, 10L, false, 7L,
                3L, "OBS 방송 세팅", AT);
        LectureObjectionWithdrawnEvent withdrawn = new LectureObjectionWithdrawnEvent("WITHDRAW:1", 1L, 10L, 7L, 3L,
                "OBS 방송 세팅", AT);
        LectureObjectionReturnCompletedEvent returned = new LectureObjectionReturnCompletedEvent(1L, 10L, 7L, 3L,
                "OBS 방송 세팅", AT);
        LectureObjectionReturnActionRequiredEvent delayed = new LectureObjectionReturnActionRequiredEvent(1L, 10L, 7L,
                3L, "OBS 방송 세팅", 2, AT);

        assertThat(jsonMapper.writeValueAsString(resolved)).contains(
                "\"buyerUserId\":7", "\"sellerUserId\":3", "\"lectureTitle\":\"OBS 방송 세팅\"",
                "\"resolvedAt\":\"2026-11-10T10:30:00+09:00\"");
        assertThat(jsonMapper.writeValueAsString(delayed)).contains("\"returnDelaySequence\":2",
                "\"occurredAt\":\"2026-11-10T10:30:00+09:00\"");
        assertThat(roundTrip(resolved)).isEqualTo(resolved);
        assertThat(roundTrip(withdrawn)).isEqualTo(withdrawn);
        assertThat(roundTrip(returned)).isEqualTo(returned);
        assertThat(roundTrip(delayed)).isEqualTo(delayed);
    }

    @Test
    void 다른_offset으로_만들어도_같은_시각의_KST_offset으로_맞춘다() {
        OffsetDateTime utc = AT.withOffsetSameInstant(ZoneOffset.UTC);

        LectureObjectionResolvedEvent event = new LectureObjectionResolvedEvent("RESOLVE:1", 1L, 10L, true, 7L, 3L,
                "OBS 방송 세팅", utc);

        assertThat(event.resolvedAt()).isEqualTo(AT);
        assertThat(event.resolvedAt().getOffset()).isEqualTo(KST);
    }

    private <T> T read(String json, Class<T> type) {
        return jsonMapper.readValue(json, type);
    }

    @SuppressWarnings("unchecked")
    private <T> T roundTrip(T event) {
        return (T) jsonMapper.readValue(jsonMapper.writeValueAsString(event), event.getClass());
    }
}
