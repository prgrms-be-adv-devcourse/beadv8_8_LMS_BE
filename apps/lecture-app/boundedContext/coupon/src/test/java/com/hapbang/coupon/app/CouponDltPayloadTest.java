package com.hapbang.coupon.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

// 역직렬화에 실패한 메시지의 받은 바이트를 격리 기록 원문으로 바꾸는 규칙. 발급·주문 recorder가 함께 쓴다
class CouponDltPayloadTest {

    @Test
    void UTF_8로_읽히고_NUL이_없으면_문자열_그대로다() {
        assertThat(CouponDltPayload.rawText("{\"couponId\":1,\"이름\":\"할인\"".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("{\"couponId\":1,\"이름\":\"할인\"");
    }

    @Test
    void NUL이_있거나_UTF_8이_아니거나_접두어로_시작하면_Base64로_남긴다() {
        byte[] withNul = "{\"a\":\"\u0000\"}".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = {(byte) 0xC3, (byte) 0x28};
        byte[] prefixed = "base64:abc".getBytes(StandardCharsets.UTF_8);

        assertThat(CouponDltPayload.rawText(withNul)).isEqualTo("base64:" + Base64.getEncoder().encodeToString(withNul));
        assertThat(CouponDltPayload.rawText(malformed))
                .isEqualTo("base64:" + Base64.getEncoder().encodeToString(malformed));
        assertThat(CouponDltPayload.rawText(prefixed))
                .isEqualTo("base64:" + Base64.getEncoder().encodeToString(prefixed));
        assertThat(Base64.getDecoder().decode(CouponDltPayload.rawText(withNul).substring("base64:".length())))
                .isEqualTo(withNul);
    }
}
