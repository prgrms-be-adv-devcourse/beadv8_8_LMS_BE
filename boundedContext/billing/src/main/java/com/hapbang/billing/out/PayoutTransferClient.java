package com.hapbang.billing.out;

// 환전 KRW 송금 요청·결과 조회 계약 (#288). 지금 구현은 같은 애플리케이션의 Mock 은행을 HTTP로 부르는 MockPayoutHttpClient 하나다.
// 후속 정산 Scheduler·환전 송금 UseCase가 이 인터페이스로 부른다. 지켜야 할 호출 규칙:
// 1) DB 트랜잭션 밖에서만 부른다(지갑·환전·보류 락을 쥔 채 HTTP를 기다리지 않는다). 트랜잭션 안에서 부르면 IllegalStateException
// 2) 지급 요청 ID(UUID)는 송금 건마다 한 번 만들어 저장(ExchangePayout.payout_request_id)하고 커밋한 뒤 보낸다. 결과를 몰라도
//    (Unknown·Processing) 새 ID로 다시 보내지 않고 같은 ID로 조회(find)하거나 같은 ID로 다시 보낸다
// 3) 클라이언트는 재시도하지 않는다(호출 1번 = HTTP 요청 1번). 재시도 횟수·간격은 호출한 쪽 정책이다
public interface PayoutTransferClient {

    // 송금을 요청한다.
    PayoutTransferResult send(PayoutTransferRequest request);

    // 지급 요청 ID로 앞선 송금 결과를 조회한다. 기록이 없으면 NotFound(보내지 않았거나 처리 전에 실패 — 무조건 실패·재송금으로 보지 않는다)
    PayoutTransferResult find(String payoutRequestId);
}
