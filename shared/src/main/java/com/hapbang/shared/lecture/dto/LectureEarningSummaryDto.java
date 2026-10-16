package com.hapbang.shared.lecture.dto;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 판매자 한 명의 아직 지급하지 않은 판매 수익을 상태별로 합한 값 (#300). 페이지와 관계없이 전체 항목 기준이다.
 * 지급 완료·구매 취소·반환은 넣지 않는다.
 *
 * @param confirmationPending 구매 확정 대기
 * @param scheduled           지급 예정
 * @param onHold              지급 중단(이의 제기)
 * @param processing          처리 중
 * @param failed              지급 실패
 * @param asOf                집계 기준 시각
 */
public record LectureEarningSummaryDto(
        LectureEarningAmountDto confirmationPending,
        LectureEarningAmountDto scheduled,
        LectureEarningAmountDto onHold,
        LectureEarningAmountDto processing,
        LectureEarningAmountDto failed,
        OffsetDateTime asOf
) {

    // 모든 상태를 0으로라도 채워 보낸다. 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public LectureEarningSummaryDto {
        Objects.requireNonNull(confirmationPending, "confirmationPending은 필수입니다.");
        Objects.requireNonNull(scheduled, "scheduled는 필수입니다.");
        Objects.requireNonNull(onHold, "onHold는 필수입니다.");
        Objects.requireNonNull(processing, "processing은 필수입니다.");
        Objects.requireNonNull(failed, "failed는 필수입니다.");
        Objects.requireNonNull(asOf, "asOf는 필수입니다.");
    }
}
