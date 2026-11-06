package com.hapbang.shared.lecture.dto;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * AI 추천용 장바구니 항목 하나 (#712, D82). {@code CartItemChangedEvent}와 장바구니 동기화 내부 API가 같은 값을 싣는다.
 * 시각은 KST offset으로 맞춘다({@link LecturePurchaseSyncDto}와 같음). 다시 담으면 새 항목(새 ID)이다.
 * 강의가 삭제·판매 중지되어도 항목은 {@code deleted=false}로 남는다.
 *
 * @param id        장바구니 항목 ID
 * @param userId    장바구니 주인 회원 ID
 * @param lectureId 강의 ID
 * @param deleted   장바구니에서 뺐는지(직접 빼기, 결제 뒤 자동 빼기)
 * @param addedAt   담은 시각
 * @param version   장바구니 항목 원본의 버전
 */
public record CartItemSyncDto(
        Long id,
        Long userId,
        Long lectureId,
        boolean deleted,
        OffsetDateTime addedAt,
        long version
) {

    public CartItemSyncDto {
        Objects.requireNonNull(id, "id는 필수입니다.");
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(lectureId, "lectureId는 필수입니다.");
        addedAt = SyncTimes.kst(Objects.requireNonNull(addedAt, "addedAt은 필수입니다."));
    }
}
