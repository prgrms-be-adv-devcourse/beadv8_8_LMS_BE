package com.hapbang.lecture.out;

import com.hapbang.lecture.domain.LectureReview;
import com.hapbang.lecture.domain.OrderItemStatus;

/**
 * 리뷰와 그 주문 항목의 상태 (#712). 리뷰 동기화 내부 API가 평점 반영 여부를 정할 때 쓴다.
 *
 * @param review     리뷰
 * @param itemStatus 리뷰를 쓴 주문 항목의 지금 상태
 */
public record ReviewWithItemStatus(LectureReview review, OrderItemStatus itemStatus) {
}
