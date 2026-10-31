package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

// 환전 송금의 현재 시각 (#304): 정산 예정 시각 도래 판단, 송금 시도·결과 확인·완료·실패 시각. 테스트는 이 빈을 바꿔 시간 경계를 검증한다.
@Component
public class ExchangePayoutClock {

    public Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
