package com.hapbang.lecture.out;

import com.hapbang.lecture.domain.LectureOrderStatus;
import com.hapbang.lecture.domain.OrderCouponWork;

/** 관리자 쿠폰 작업 목록 한 줄: 작업과 그 주문의 상태 */
public record OrderCouponWorkRow(OrderCouponWork work, LectureOrderStatus orderStatus) {
}
