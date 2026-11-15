package com.hapbang.auxiliary.chatbot.app;

import org.jspecify.annotations.Nullable;

/**
 * 추천 관심사 문장의 규칙 (#718, #720). 강의·인플루언서의 위젯·버튼 API 검증, 버튼 쿼리 확장, 강의 Tool이 같은 규칙을 쓴다.
 * 요청 검증 애노테이션이 컴파일 시점 상수를 써야 해서 설정이 아니라 상수다. 인플루언서 Tool의 자르기 길이는 아직 설정값
 * ({@code hapbang.chatbot.recommend.influencer.interest-max-length})이고 합치는 일은 #835에서 한다.
 */
public final class RecommendInterest {

    /** 관심사 최대 글자 수(앞뒤 공백 제외) */
    public static final int MAX_LENGTH = 100;

    /** 요청 검증: 앞뒤 공백을 뺀 길이가 1~{@link #MAX_LENGTH}자. 첫·마지막 글자가 공백 아닌 글자이고 그 사이가 최대 길이−2자 */
    public static final String PATTERN = "(?s)\\s*\\S(?:.{0," + (MAX_LENGTH - 2) + "}\\S)?\\s*";

    public static final String MESSAGE = "관심사는 앞뒤 공백을 빼고 1~" + MAX_LENGTH + "자로 써 주세요.";

    private RecommendInterest() {
    }

    /**
     * 입력한 관심사를 추천에 넘길 값으로 바꾼다.
     *
     * @return 없거나 공백뿐이면 null, 아니면 앞뒤 공백을 뺀 값
     */
    public static @Nullable String normalize(@Nullable String interest) {
        return interest == null || interest.isBlank() ? null : interest.strip();
    }
}
