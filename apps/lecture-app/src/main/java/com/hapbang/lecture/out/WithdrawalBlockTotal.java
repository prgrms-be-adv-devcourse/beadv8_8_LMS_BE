package com.hapbang.lecture.out;

import java.time.LocalDateTime;

import com.hapbang.lecture.domain.LectureWithdrawalBlockCode;

/**
 * 탈퇴가 막힌 이유 하나에 걸린 주문 항목 집계 (#625)
 *
 * @param latestPayoutScheduledAt 가장 늦은 지급 예정 시각 (KST). 항목이 없으면 {@code null}
 * @param undatableCount          지급 예정 시각을 정할 수 없는 항목(지급 중단·지급 실패, 수익 API에서 시각이 {@code null}) 수
 */
public record WithdrawalBlockTotal(LectureWithdrawalBlockCode code, long count, LocalDateTime latestPayoutScheduledAt,
                                   long undatableCount) {

    /** 쿼리 결과용. 이유는 {@link LectureWithdrawalBlockCode} 상수 이름이다 */
    public WithdrawalBlockTotal(String code, Long count, LocalDateTime latestPayoutScheduledAt, Long undatableCount) {
        this(LectureWithdrawalBlockCode.valueOf(code), count, latestPayoutScheduledAt, undatableCount);
    }
}
