package com.hapbang.lecture.out;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;

/** 판매 수익 목록 한 줄 (#300). 상태와 상태 변경 시각은 쿼리가 {@link LectureOrderRepository#EARNING_STATUS}로 분류한다 */
public record EarningRow(OrderItem item, LectureEarningStatus status, LocalDateTime statusUpdatedAt) {

    /** 쿼리 결과용. 상태는 {@link LectureEarningStatus} 상수 이름이다 */
    public EarningRow(OrderItem item, String status, LocalDateTime statusUpdatedAt) {
        this(item, LectureEarningStatus.valueOf(status), statusUpdatedAt);
    }
}
