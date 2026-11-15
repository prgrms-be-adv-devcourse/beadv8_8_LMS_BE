package com.hapbang.auxiliary.chatbot.domain;

/**
 * 공개된 출연 리뷰 사본 한 행에 넣을 값 (#716). 리뷰는 공개 뒤 바뀌지 않아 version이 없고 {@code reviewId}로 한 번만 넣는다.
 * <p>
 * 리뷰 공개 이벤트에는 쓴 사람·역할·공개 시각도 있지만, 싱크 DTO와 같은 4필드만 뽑는다(출연 #713 계약, app의 {@code CopyMapper}).
 * 그래야 이벤트로 받은 값과 싱크로 받은 값의 해시가 같다.
 */
public record AppearanceReviewCopy(
        long reviewId,
        long appearanceId,
        long revieweeUserId,
        int rating,
        String hash
) {
}
