package com.hapbang.coupon.in.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 쿠폰 주문 소비자가 받지 않는 강의 이벤트를 읽어 두는 빈 레코드. 본문의 필드는 읽지 않고 버린다
 * ({@link CouponOrderEventTypeResolver}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IgnoredLectureEvent() {
}
