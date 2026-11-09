package com.hapbang.auxiliary.chatbot.domain;

import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

/**
 * 공개된 출연 리뷰 사본 한 행에 넣을 값 (#716). 리뷰는 공개 뒤 바뀌지 않아 version이 없고 {@code reviewId}로 한 번만 넣는다.
 * <p>
 * 리뷰 공개 이벤트에는 쓴 사람·역할·공개 시각도 있지만, 싱크 DTO({@link AppearanceReviewSyncDto})와 같은 4필드만 뽑는다(출연 #713 계약).
 * 그래야 이벤트로 받은 값과 싱크로 받은 값의 해시가 같다.
 */
public record AppearanceReviewCopy(
        long reviewId,
        long appearanceId,
        long revieweeUserId,
        int rating,
        String hash
) {

    public static AppearanceReviewCopy from(AppearanceReviewSyncDto dto) {
        return new AppearanceReviewCopy(dto.reviewId(), dto.appearanceId(), dto.revieweeUserId(), dto.rating(),
                CopyHash.of(dto));
    }

    /** 리뷰 공개 이벤트에서 싱크 DTO의 4필드만 뽑는다. 필수 값이 없으면 {@link NullPointerException} */
    public static AppearanceReviewCopy from(AppearanceReviewPublishedEvent event) {
        return from(new AppearanceReviewSyncDto(event.reviewId(), event.appearanceId(), event.revieweeUserId(),
                event.rating()));
    }
}
