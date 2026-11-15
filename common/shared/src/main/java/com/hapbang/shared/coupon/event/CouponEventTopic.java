package com.hapbang.shared.coupon.event;

/**
 * 쿠폰 이벤트 Kafka 토픽(ADR 0001). 쿠폰 모듈이 내보내는 이벤트 타입을 이 토픽 하나에 섞는다. 지금은 {@link CouponIssueRequested}뿐이다.
 * key는 쿠폰 ID(십진 문자열)다: 같은 쿠폰의 발급 요청은 같은 partition으로 가 순서대로 처리된다. 토픽·헤더 규칙은
 * docs/architecture/infrastructure/kafka.md를 따른다.
 */
public final class CouponEventTopic {

    public static final String NAME = "coupon.events.v1";

    private CouponEventTopic() {
    }
}
