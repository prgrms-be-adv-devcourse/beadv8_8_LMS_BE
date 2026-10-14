package com.hapbang.lecture.domain;

/**
 * 재생 동의 전에 영상·자료를 보려 할 때 (D7·D8, D38).
 * 프론트가 바로 재생 동의 API를 호출할 수 있게 응답에 {@code orderId}·{@code orderItemId}를 싣는다.
 */
public class PlayConsentRequiredException extends LectureException {

    public PlayConsentRequiredException(OrderItem item) {
        super(LectureErrorCode.PLAY_CONSENT_REQUIRED);
        addProperty("orderId", item.getOrder().getId());
        addProperty("orderItemId", item.getId());
    }
}
