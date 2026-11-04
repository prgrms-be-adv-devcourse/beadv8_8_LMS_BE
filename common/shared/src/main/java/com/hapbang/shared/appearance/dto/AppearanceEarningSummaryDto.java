package com.hapbang.shared.appearance.dto;

import java.util.Objects;

/**
 * 게스트 한 명의 아직 받지 못한 출연 수익을 상태별로 합한 값 (#301). 페이지와 관계없이 전체 출연 기준이다.
 * 지급 완료·불참·합의 취소는 넣지 않는다.
 * <p>
 * 강의 요약에 있는 {@code asOf}(분류 기준 시각)는 두지 않는다. 출연 분류는 저장된 상태만 보고 조회 시각에 기대지 않는다.
 *
 * @param confirmationPending 확인 대기
 * @param scheduled           지급 예정. 출연에는 없어 항상 0
 * @param onHold              취소 요청 처리 중
 * @param processing          처리 중
 * @param failed              지급 실패
 */
public record AppearanceEarningSummaryDto(
        AppearanceEarningAmountDto confirmationPending,
        AppearanceEarningAmountDto scheduled,
        AppearanceEarningAmountDto onHold,
        AppearanceEarningAmountDto processing,
        AppearanceEarningAmountDto failed
) {

    // 모든 상태를 0으로라도 채워 보낸다. 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public AppearanceEarningSummaryDto {
        Objects.requireNonNull(confirmationPending, "confirmationPending은 필수입니다.");
        Objects.requireNonNull(scheduled, "scheduled는 필수입니다.");
        Objects.requireNonNull(onHold, "onHold는 필수입니다.");
        Objects.requireNonNull(processing, "processing은 필수입니다.");
        Objects.requireNonNull(failed, "failed는 필수입니다.");
    }
}
