package com.hapbang.auxiliary.chatbot.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사본 값의 해시와 시각 맞춤 (#716). DTO → 사본 변환의 해시는 {@code app.CopyMapperTest} */
class CopyHashTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 20, 20, 0, 0, 123_456_789);

    @Test
    @DisplayName("시각: offset 없는 KST와 +09:00 offset 값은 같은 KST LocalDateTime(마이크로초)이 된다. 다른 offset은 KST로 옮긴다")
    void times() {
        LocalDateTime expected = LocalDateTime.of(2026, 11, 20, 20, 0, 0, 123_456_000);
        assertThat(CopyTimes.kst(AT)).isEqualTo(expected);
        assertThat(CopyTimes.kst(OffsetDateTime.of(AT, ZoneOffset.ofHours(9)))).isEqualTo(expected);
        assertThat(CopyTimes.kst(OffsetDateTime.of(AT.minusHours(9), ZoneOffset.UTC))).isEqualTo(expected);
        assertThat(CopyTimes.kst((LocalDateTime) null)).isNull();
    }

    @Test
    @DisplayName("같은 시각이면 offset 유무·나노초 차이와 상관없이 해시가 같다")
    void sameInstantSameHash() {
        record Value(Long id, LocalDateTime at) {
        }
        String fromLocal = CopyHash.of(new Value(1L, CopyTimes.kst(AT)));
        String fromOffset = CopyHash.of(new Value(1L, CopyTimes.kst(OffsetDateTime.of(AT.plusNanos(1), ZoneOffset.ofHours(9)))));
        assertThat(fromLocal).isEqualTo(fromOffset).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("필드 이름 순으로 정렬하고 null도 쓴다")
    void sortedJson() {
        record Value(String b, Long a, String c) {
        }
        assertThat(CopyHash.json(new Value("x", 1L, null))).isEqualTo("{\"a\":1,\"b\":\"x\",\"c\":null}");
        assertThat(CopyHash.of(new Value("x", 1L, null))).isNotEqualTo(CopyHash.of(new Value("x", 1L, "")));
    }
}
