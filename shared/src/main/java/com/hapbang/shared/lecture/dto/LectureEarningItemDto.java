package com.hapbang.shared.lecture.dto;

import java.time.OffsetDateTime;
import java.util.Objects;

import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.domain.LectureEarningType;

/**
 * 판매 수익 한 건 = 주문 항목 하나 (#300).
 *
 * @param sourceType        수익 원천 종류. 강의는 항상 {@value #SOURCE_TYPE}
 * @param sourceId          주문 항목 ID
 * @param earningType       수익 종류
 * @param title             주문 시점 강의 제목
 * @param amountHp          받을 금액 (주문 시점 가격, HP)
 * @param status            지급 진행 상태
 * @param payoutScheduledAt 지급 예정 시각. 지급 중단·실패·취소처럼 시각을 정할 수 없으면 {@code null}
 * @param statusUpdatedAt   지금 상태가 된 시각
 */
public record LectureEarningItemDto(
        String sourceType,
        Long sourceId,
        LectureEarningType earningType,
        String title,
        long amountHp,
        LectureEarningStatus status,
        OffsetDateTime payoutScheduledAt,
        OffsetDateTime statusUpdatedAt
) {

    // 지급 예정 시각만 비어 있을 수 있다. 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public LectureEarningItemDto {
        Objects.requireNonNull(sourceType, "sourceType은 필수입니다.");
        Objects.requireNonNull(sourceId, "sourceId는 필수입니다.");
        Objects.requireNonNull(earningType, "earningType은 필수입니다.");
        Objects.requireNonNull(title, "title은 필수입니다.");
        Objects.requireNonNull(status, "status는 필수입니다.");
        Objects.requireNonNull(statusUpdatedAt, "statusUpdatedAt은 필수입니다.");
    }

    /** 지급 원천 이름과 같다 (billing {@code SettlementSourceType.LECTURE_ORDER_ITEM}) */
    public static final String SOURCE_TYPE = "LECTURE_ORDER_ITEM";
}
