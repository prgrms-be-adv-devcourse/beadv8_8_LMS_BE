package com.hapbang.appearance.app.dto;

import java.time.LocalDateTime;

import com.hapbang.appearance.domain.AppearanceEarningStatus;
import com.hapbang.appearance.domain.AppearanceEarningType;
import com.hapbang.shared.billing.domain.SettlementSourceType;

/**
 * 출연 수익 한 건 (#301). Billing 공통 계약 {@code EarningItemDto}와 같은 모양이다 (공통 DTO가 들어오면 Facade에서 바꿔 담는다).
 * 시각은 모두 Asia/Seoul 기준 {@code LocalDateTime}이다 (APR-39 N1).
 *
 * @param sourceType        Billing 지급·위약금 요청의 원천과 같은 값 (APPEARANCE_REQUEST / RECRUIT_APPEARANCE)
 * @param sourceId          출연 ID (Billing 지급·위약금 요청의 원천 ID와 같다)
 * @param title             출처 요청서·모집글의 현재 제목
 * @param amountHp          출연료, 위약금이면 위약금
 * @param payoutScheduledAt 출연은 지급 시각이 정해지지 않아(호스트가 7일 안에 언제든 확인) 항상 null
 * @param statusUpdatedAt   상태 기준 시각: 작업 완료 → 확인 → 취소 → 불참 → 출연 수정 시각 중 처음 있는 값
 */
public record AppearanceEarningItemResDto(
        SettlementSourceType sourceType,
        Long sourceId,
        AppearanceEarningType earningType,
        String title,
        long amountHp,
        AppearanceEarningStatus status,
        LocalDateTime payoutScheduledAt,
        LocalDateTime statusUpdatedAt
) {
}
