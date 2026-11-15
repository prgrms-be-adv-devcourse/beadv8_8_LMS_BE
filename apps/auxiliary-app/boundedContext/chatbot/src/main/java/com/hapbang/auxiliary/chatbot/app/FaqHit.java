package com.hapbang.auxiliary.chatbot.app;

/**
 * FAQ 검색 결과 하나. {@code searchFaq} Tool이 모델에게 그대로 돌려주는 값이다(JSON으로 직렬화된다).
 * 모델이 보면 안 되는 값(정책 근거 메모, 색인 정보)은 넣지 않는다.
 *
 * @param score 코사인 유사도(높을수록 가깝다)
 */
public record FaqHit(String code, String question, String answer, double score) {
}
