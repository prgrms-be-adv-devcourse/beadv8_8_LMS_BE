package com.hapbang.shared.lecture.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

import tools.jackson.databind.json.JsonMapper;

/**
 * 강의 동기화 이벤트의 가격 필드(#885) JSON 계약. 가격은 같은 eventVersion 안에서 더한 선택 필드라,
 * 이 필드가 생기기 전에 발행된 메시지(배포 중 옛 강의 앱, 발행 기록 재발행)도 실패 없이 읽고 가격은 {@code null}이다.
 */
class LectureChangedEventJsonTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void 가격을_priceHp로_쓰고_다시_읽어도_같다() {
        LectureChangedEvent event = new LectureChangedEvent(new LectureSyncDto(11L, 3L, "방송 장비 세팅", null, 10_000L,
                true, false, List.of(new LectureContentSyncDto(21L, "1강", 1)), 4L));

        String json = jsonMapper.writeValueAsString(event);

        assertThat(json).contains("\"priceHp\":10000");
        assertThat(jsonMapper.readValue(json, LectureChangedEvent.class)).isEqualTo(event);
    }

    @Test
    void 가격이_없는_옛_메시지도_읽고_가격은_null이다() {
        String old = """
                {"lecture":{"id":11,"sellerUserId":3,"title":"방송 장비 세팅","onSale":true,"deleted":false,
                  "contents":[{"id":21,"title":"1강","sequence":1}],"version":4}}""";

        LectureChangedEvent event = jsonMapper.readValue(old, LectureChangedEvent.class);

        assertThat(event.lecture().priceHp()).isNull();
        assertThat(event.lecture().version()).isEqualTo(4L);
    }
}
