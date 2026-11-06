package com.hapbang.shared.lecture.dto;

import java.time.OffsetDateTime;
import java.util.Objects;

import com.hapbang.shared.lecture.domain.LecturePurchaseResult;

/**
 * AI 추천용 구매 한 건 = 주문 항목 하나 (#712, D82). {@code LecturePurchaseChangedEvent}와 구매 동기화 내부 API가 같은 값을 싣는다.
 * 금액·이의 제기 정보는 싣지 않는다. 시각은 KST offset({@code +09:00})으로 맞춰 두어, JSON을 읽은 쪽의 Jackson이
 * offset을 UTC로 바꿔 읽어도 같은 구매면 {@code equals}·{@code hashCode}가 같다(받는 쪽이 DTO 해시로 어긋남을 비교한다).
 *
 * @param orderItemId 주문 항목 ID
 * @param buyerUserId 구매자 회원 ID
 * @param lectureId   강의 ID
 * @param result      강의가 줄인 구매 결과
 * @param orderedAt   주문한 시각
 * @param purchasedAt 결제가 끝나 구매 완료된 시각. 결제 전·결제 실패면 {@code null}
 * @param version     주문 항목 원본의 버전
 */
public record LecturePurchaseSyncDto(
        Long orderItemId,
        Long buyerUserId,
        Long lectureId,
        LecturePurchaseResult result,
        OffsetDateTime orderedAt,
        OffsetDateTime purchasedAt,
        long version
) {

    public LecturePurchaseSyncDto {
        Objects.requireNonNull(orderItemId, "orderItemId는 필수입니다.");
        Objects.requireNonNull(buyerUserId, "buyerUserId는 필수입니다.");
        Objects.requireNonNull(lectureId, "lectureId는 필수입니다.");
        Objects.requireNonNull(result, "result는 필수입니다.");
        orderedAt = SyncTimes.kst(Objects.requireNonNull(orderedAt, "orderedAt은 필수입니다."));
        purchasedAt = SyncTimes.kst(purchasedAt);
    }
}
