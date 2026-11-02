package com.hapbang.lecture.out;

import com.hapbang.shared.lecture.domain.LectureEarningStatus;

/** 판매자의 판매 수익 중 한 상태의 건수와 금액 합계 (#300) */
public record EarningStatusTotal(LectureEarningStatus status, long count, long amountHp) {

    /** 쿼리 결과용. 상태는 {@link LectureEarningStatus} 상수 이름이다 */
    public EarningStatusTotal(String status, Long count, Long amountHp) {
        this(LectureEarningStatus.valueOf(status), count, amountHp);
    }
}
