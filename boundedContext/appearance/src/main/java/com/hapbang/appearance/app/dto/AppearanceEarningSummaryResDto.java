package com.hapbang.appearance.app.dto;

/**
 * 게스트의 출연 수익 요약 (#301): 아직 받지 못한 수익의 상태별 금액·건수. 페이지와 상관없이 전체 기준이다.
 * Billing 공통 계약 {@code EarningSummaryDto}와 같은 모양이다 (공통 DTO가 들어오면 Facade에서 바꿔 담는다).
 *
 * @param scheduled 출연은 확인하는 순간 지급을 실행해 "지급 예정" 단계가 없으므로 항상 0
 */
public record AppearanceEarningSummaryResDto(
        AppearanceEarningBucketResDto confirmationPending,
        AppearanceEarningBucketResDto scheduled,
        AppearanceEarningBucketResDto onHold,
        AppearanceEarningBucketResDto processing,
        AppearanceEarningBucketResDto failed
) {
}
