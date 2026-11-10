package com.hapbang.coupon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

// DLT 관리자 처리 설정: 재처리 제한 시간 기본값과, 선점 lease보다 짧아야 한다는 검사(설정을 묶을 때라 앱 기동에서 걸린다)
class CouponDltPropertiesTest {

    @Test
    void 기본값은_lease_1분_재처리_제한_시간_30초다() {
        CouponDltProperties defaults = CouponDltProperties.defaults();

        assertThat(defaults.dltReplayLease()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.dltReplayTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.dltReplayTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    void 재처리_제한_시간이_lease보다_짧지_않으면_거부한다() {
        assertThatThrownBy(() -> new CouponDltProperties(Duration.ofMinutes(1), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dlt-replay-timeout");
        // 초 단위로 올린 실제 값으로 비교한다(59.5초 → 60초)
        assertThatThrownBy(() -> new CouponDltProperties(Duration.ofMinutes(1), Duration.ofMillis(59_500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CouponDltProperties(Duration.ofMinutes(1), Duration.ofSeconds(59)).dltReplayTimeoutSeconds())
                .isEqualTo(59);
    }

    @Test
    void 재처리_제한_시간이_1초보다_짧으면_거부한다() {
        assertThatThrownBy(() -> new CouponDltProperties(null, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
