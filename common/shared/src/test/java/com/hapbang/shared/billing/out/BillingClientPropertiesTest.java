package com.hapbang.shared.billing.out;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

// 지갑 내부 API Client 설정(hapbang.clients.billing.*)의 필수 값과 기본값 (#272)
class BillingClientPropertiesTest {

    @Test
    void base_url이_없으면_설정_생성이_실패한다() {
        assertThatThrownBy(() -> new BillingClientProperties(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hapbang.clients.billing.base-url");
        assertThatThrownBy(() -> new BillingClientProperties(" ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeout을_주지_않으면_2초와_5초다() {
        BillingClientProperties properties = new BillingClientProperties("http://localhost:8082", null, null);

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
