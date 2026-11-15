package com.hapbang.coupon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

// DLT 관리자 처리 설정: 재처리 제한 시간 기본값과, 선점 lease보다 짧아야 한다는 검사(설정을 묶을 때라 앱 기동에서 걸린다)
class CouponDltPropertiesTest {

    @Test
    void 기본값은_lease_1분_재처리_제한_시간_25초다() {
        CouponDltProperties defaults = CouponDltProperties.defaults();

        assertThat(defaults.dltReplayLease()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.dltReplayTimeout()).isEqualTo(Duration.ofSeconds(25));
        assertThat(defaults.dltReplayTimeoutSeconds()).isEqualTo(25);
    }

    @Test
    void 재처리_제한_시간의_두_배가_lease보다_짧지_않으면_거부한다() {
        // 두 배 60초 = lease 1분이라 거부(멱등 충돌 뒤 한 번 더 도는 트랜잭션까지 lease 안에 끝나야 한다)
        assertThatThrownBy(() -> new CouponDltProperties(Duration.ofMinutes(1), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dlt-replay-timeout");
        // 초 단위로 올린 실제 값으로 비교한다(29.5초 → 30초, 두 배 60초)
        assertThatThrownBy(() -> new CouponDltProperties(Duration.ofMinutes(1), Duration.ofMillis(29_500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CouponDltProperties(Duration.ofMinutes(1), Duration.ofSeconds(25)).dltReplayTimeoutSeconds())
                .isEqualTo(25);
        assertThat(new CouponDltProperties(Duration.ofMinutes(1), Duration.ofSeconds(29)).dltReplayTimeoutSeconds())
                .isEqualTo(29);
    }

    @Test
    void 재처리_제한_시간이_1초보다_짧으면_거부한다() {
        assertThatThrownBy(() -> new CouponDltProperties(null, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
