package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

// 환불 복구 정책(UNKNOWN 자동 복구 한도·멱등키 안전 기간)을 판단하는 현재 시각 (#260). 테스트는 이 빈을 바꿔 시간 경계를 검증한다.
@Component
public class RefundClock {

    public Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
