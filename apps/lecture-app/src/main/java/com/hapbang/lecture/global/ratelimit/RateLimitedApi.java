package com.hapbang.lecture.global.ratelimit;

/**
 * 회원별 요청 한도를 거는 강의 사용자 API (D80). 회원 × API마다 따로 센다.
 * <p>
 * 요청 안에서 지갑을 동기로 부르는 API만 넣는다. 내부 API({@code /api/v1/internal/**})와 관리자 API는 넣지 않는다.
 * 모두 상태를 바꾸는 요청이라 한도는 설정 하나({@link RateLimitProperties#write()})다. 지표 태그는 {@link #tag()}다.
 */
public enum RateLimitedApi {
    /** 주문 {@code POST /api/v1/lectures/orders}. 항목마다 HP 보류를 요청 안에서 시도한다(D58) */
    ORDER("order"),
    /** 구매 취소 {@code POST /api/v1/lectures/orders/{orderId}/items/{orderItemId}/cancel}. 반환을 요청 안에서 시도한다(D59) */
    CANCEL("cancel");

    private final String tag;

    RateLimitedApi(String tag) {
        this.tag = tag;
    }

    /** 지표 태그에 쓰는 이름 */
    public String tag() {
        return tag;
    }
}
