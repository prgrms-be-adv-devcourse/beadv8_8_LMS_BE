package com.hapbang.coupon.app;

import java.util.Optional;

import com.hapbang.shared.coupon.event.CouponIssueRequested;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLT 격리 기록의 원문(발급 요청 이벤트 JSON) 읽기·쓰기. 재발행·영구 거절이 원문에서 이벤트를 다시 읽는다.
 * 역직렬화에 실패해 격리된 메시지는 받은 바이트 그대로라 읽지 못한다.
 */
public final class CouponDltPayload {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CouponDltPayload() {
    }

    public static String write(CouponIssueRequested event) {
        return JSON.writeValueAsString(event);
    }

    /** 원문을 발급 요청 이벤트로 읽는다. 읽지 못하면 빈 값 */
    public static Optional<CouponIssueRequested> read(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JSON.readValue(payload, CouponIssueRequested.class));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }
}
