package com.hapbang.auxiliary.chatbot.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 인플루언서를 추천한 이유 (#719). 응답으로 나가고, LLM 설명이 실패하면 {@link #description}을 그대로 이유 문장으로 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum InfluencerReason {
    SIMILAR_CATEGORY("내 콘텐츠 분야·소개와 비슷해요"),
    SIMILAR_TO_PARTNERS("함께 출연했던 인플루언서들과 비슷해요"),
    MATCHES_INTEREST("말씀하신 관심사와 잘 맞아요"),
    POPULAR("출연 경험과 리뷰 평가가 좋은 인기 인플루언서예요"),
    PAST_PARTNER("예전에 함께 출연했던 인플루언서예요");

    private final String description;
}
