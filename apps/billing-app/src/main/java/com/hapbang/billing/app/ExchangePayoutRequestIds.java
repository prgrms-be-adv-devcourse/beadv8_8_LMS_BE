package com.hapbang.billing.app;

import java.util.UUID;

import org.springframework.stereotype.Component;

// 송금 시도의 지급 요청 ID(소문자 UUID, Mock 은행 Idempotency-Key) (#304). 시도마다 한 번 만들어 exchange_payout에 저장하고
// 그 저장값으로만 송금·조회한다(결과를 모를 때 새 ID를 만들지 않는다). DB ID로 만들지 않는다: DB 초기화나 다른 환경에서 같은 ID가
// 다시 나오면 은행 쪽 기록과 섞일 수 있다. 테스트는 이 빈을 바꿔 시도별 Mock 시나리오를 미리 정한다.
@Component
public class ExchangePayoutRequestIds {

    public String next() {
        return UUID.randomUUID().toString();
    }
}
