package com.hapbang.coupon.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RecoveryOffsetsTest {

    @Test
    void partition_순서로_적고_같은_값으로_읽는다() {
        RecoveryOffsets offsets = new RecoveryOffsets(Map.of(2, 7L, 0, 12L, 1, 0L));

        assertThat(offsets.format()).isEqualTo("0:12,1:0,2:7");
        assertThat(RecoveryOffsets.parse("0:12,1:0,2:7")).isEqualTo(offsets);
        assertThat(RecoveryOffsets.parse("")).isEqualTo(RecoveryOffsets.empty());
        assertThat(RecoveryOffsets.empty().format()).isEmpty();
    }

    @Test
    void 형식이_틀리면_읽지_않는다() {
        assertThatThrownBy(() -> RecoveryOffsets.parse("0=1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecoveryOffsets.parse("0:a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecoveryOffsets.parse("0:1,0:2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecoveryOffsets.parse("0:-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 경계까지_커밋되지_않은_메시지만_세고_경계_뒤나_앞선_커밋은_세지_않는다() {
        RecoveryOffsets boundary = RecoveryOffsets.parse("0:10,1:5,2:0");

        // partition 0: 10 - 7 = 3, partition 1: 커밋 없음 → 5, partition 2: 경계보다 앞서 커밋 → 0
        assertThat(boundary.lagFrom(RecoveryOffsets.parse("0:7,2:3"))).isEqualTo(8L);
        assertThat(boundary.lagFrom(RecoveryOffsets.parse("0:10,1:5,2:0"))).isZero();
        assertThat(RecoveryOffsets.empty().lagFrom(RecoveryOffsets.parse("0:3"))).isZero();
    }
}
