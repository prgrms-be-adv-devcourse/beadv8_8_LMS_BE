package com.hapbang.billing.global.ratelimit;

/**
 * 회원별 요청 한도를 거는 지갑 사용자 API (#627, BD-140). 회원 × API마다 따로 센다.
 * <p>
 * 내부 API({@code /api/v1/internal/**}), 관리자 API, Mock 송금은 넣지 않는다. 지표 태그는 {@link #tag()}다.
 */
public enum RateLimitedApi {

    /** 충전 주문 {@code POST /api/v1/wallets/charges} */
    CHARGE_ORDER("charge-order", Group.WRITE),
    /** 충전 승인 {@code POST /api/v1/wallets/charges/confirm} */
    CHARGE_CONFIRM("charge-confirm", Group.WRITE),
    /** 환불 신청 {@code POST /api/v1/wallets/refunds} */
    REFUND_REQUEST("refund-request", Group.WRITE),
    /** 환불 취소 {@code POST /api/v1/wallets/refunds/{id}/cancel} */
    REFUND_CANCEL("refund-cancel", Group.WRITE),
    /** 환전 신청 {@code POST /api/v1/settlements/exchanges} */
    EXCHANGE_REQUEST("exchange-request", Group.WRITE),
    /** 환전 취소 {@code POST /api/v1/settlements/exchanges/{id}/cancel} */
    EXCHANGE_CANCEL("exchange-cancel", Group.WRITE),

    /** 내 지갑 {@code GET /api/v1/wallets/me} */
    WALLET("wallet", Group.READ),
    /** 내 HP 거래 내역 {@code GET /api/v1/wallets/me/transactions} */
    WALLET_TRANSACTIONS("wallet-transactions", Group.READ),
    /** 충전 조회 {@code GET /api/v1/wallets/charges/{orderId}} */
    CHARGE_GET("charge-get", Group.READ),
    /** 환불 조회 {@code GET /api/v1/wallets/refunds/{id}} */
    REFUND_GET("refund-get", Group.READ),
    /** 내 환전 목록 {@code GET /api/v1/settlements/exchanges} */
    EXCHANGE_LIST("exchange-list", Group.READ),
    /** 환전 상세 {@code GET /api/v1/settlements/exchanges/{id}} */
    EXCHANGE_GET("exchange-get", Group.READ),

    /** 지급 예정 수익 요약 {@code GET /api/v1/settlements/earnings/summary}. 요청마다 강의·출연 내부 API를 부른다 */
    EARNING_SUMMARY("earning-summary", Group.EARNING),
    /** 지급 예정 수익 목록 {@code GET /api/v1/settlements/earnings}. 요청마다 강의·출연 내부 API를 부른다 */
    EARNING_LIST("earning-list", Group.EARNING);

    /** 한도 종류. 종류마다 설정 하나({@link RateLimitProperties})로 분당 한도를 정한다 */
    public enum Group {
        /** 상태를 바꾸는 신청·취소 (기본 분당 10) */
        WRITE,
        /** 일반 조회 (기본 분당 60, 1초 간격 polling 통과) */
        READ,
        /** 다른 앱을 부르는 수익 조회 (기본 분당 20) */
        EARNING
    }

    private final String tag;
    private final Group group;

    RateLimitedApi(String tag, Group group) {
        this.tag = tag;
        this.group = group;
    }

    /** 지표 태그에 쓰는 이름 */
    public String tag() {
        return tag;
    }

    public Group group() {
        return group;
    }
}
