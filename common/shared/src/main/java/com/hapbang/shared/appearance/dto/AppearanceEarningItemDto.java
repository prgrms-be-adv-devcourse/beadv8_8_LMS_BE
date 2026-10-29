package com.hapbang.shared.appearance.dto;

import java.time.LocalDateTime;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.domain.AppearanceEarningType;

/**
 * 출연 수익 한 건 = 출연 하나 (#301). 시각은 모두 KST {@code LocalDateTime}이다 (offset 없음, ISSUE APR-39 N1).
 *
 * @param sourceType        수익 원천 종류. 출연은 항상 {@value #SOURCE_TYPE}
 * @param sourceId          출연 ID. Billing 지급·위약금 기록의 원천 ID와 같다 (요청 출처·모집 출처 모두 출연 ID)
 * @param earningType       수익 종류
 * @param title             출처 요청서·모집글의 현재 제목
 * @param amountHp          받을 금액. 출연료, 일방 취소면 위약금 (HP)
 * @param status            지급 상태
 * @param payoutScheduledAt 지급 예정 시각. 출연은 정해지지 않아(호스트가 확인 기간 안에 언제든 확인) 항상 {@code null}이고,
 *                          계약(docs/api/internal-earnings.md)대로 응답 JSON에서 빠진다 (#408)
 * @param statusUpdatedAt   지금 상태가 된 시각: 작업 완료 → 확인 → 취소 → 불참 → 출연 수정 시각 중 처음 있는 값
 */
public record AppearanceEarningItemDto(
        String sourceType,
        Long sourceId,
        AppearanceEarningType earningType,
        String title,
        long amountHp,
        AppearanceEarningStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime payoutScheduledAt,
        LocalDateTime statusUpdatedAt
) {

    // 지급 예정 시각만 비어 있을 수 있다. 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public AppearanceEarningItemDto {
        Objects.requireNonNull(sourceType, "sourceType은 필수입니다.");
        Objects.requireNonNull(sourceId, "sourceId는 필수입니다.");
        Objects.requireNonNull(earningType, "earningType은 필수입니다.");
        Objects.requireNonNull(title, "title은 필수입니다.");
        Objects.requireNonNull(status, "status는 필수입니다.");
        Objects.requireNonNull(statusUpdatedAt, "statusUpdatedAt은 필수입니다.");
    }

    /** 수익 출처. 강의는 {@code LECTURE}다 (docs/api/internal-earnings.md) */
    public static final String SOURCE_TYPE = "APPEARANCE";
}
