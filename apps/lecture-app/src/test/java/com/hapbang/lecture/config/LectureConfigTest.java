package com.hapbang.lecture.config;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.Test;

// 강의 시계는 DB(timestamp, 마이크로초)와 같은 정밀도다 (#712, D82). 나노초가 남으면 커밋 전 엔티티로 만든 동기화 이벤트와
// DB에서 읽은 동기화 내부 API의 시각이 같은 버전인데도 달라진다. Linux JDK 시계는 나노초까지 주고 macOS는 마이크로초까지만 주므로
// 실제 시계 대신 나노초가 있는 고정 시계로 확인한다
class LectureConfigTest {

    @Test
    void 강의_시계는_마이크로초_아래를_버린다() {
        Clock nanos = Clock.fixed(Instant.parse("2026-11-06T00:00:00.123456789Z"), LectureConfig.ZONE);

        assertThat(LectureConfig.toMicros(nanos).instant()).isEqualTo(Instant.parse("2026-11-06T00:00:00.123456Z"));
    }

    @Test
    void 강의_시계는_KST다() {
        assertThat(new LectureConfig().lectureClock().getZone()).isEqualTo(LectureConfig.ZONE);
    }
}
