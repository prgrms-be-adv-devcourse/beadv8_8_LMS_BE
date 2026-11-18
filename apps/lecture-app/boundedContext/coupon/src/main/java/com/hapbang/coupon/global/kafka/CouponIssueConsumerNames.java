package com.hapbang.coupon.global.kafka;

import com.hapbang.shared.coupon.event.CouponEventTopic;

/**
 * 발급 확정 소비자의 이름. 소비 설정({@code in/kafka/CouponIssueConsumerKafkaConfig})과 복구 경계 확인
 * ({@code out/kafka/KafkaCouponIssueBoundaryProbe})이 함께 쓰므로 in과 out 사이에 둔다.
 */
public final class CouponIssueConsumerNames {

    /** 소비 그룹. 이름은 {@code <앱>.<목적>}이다 */
    public static final String GROUP = "lecture-app.coupon-issue";

    /** 그룹별 DLT: {@code <토픽>.<그룹>.DLT} */
    public static final String DEAD_LETTER_TOPIC = CouponEventTopic.NAME + "." + GROUP + ".DLT";

    private CouponIssueConsumerNames() {
    }
}
