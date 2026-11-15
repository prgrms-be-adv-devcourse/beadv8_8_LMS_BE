package com.hapbang.auxiliary.chatbot.domain;

import org.jspecify.annotations.Nullable;

/**
 * 강의 추천 이유 코드 (#718). 계산 중에 이미 아는 "왜 이 강의가 나왔는지"를 카드마다 하나 남긴다.
 * 카드 문구, 이유별 클릭률(GA4), 맞춤이 아닌 카드 구분({@link #POPULAR})에 쓴다.
 */
public enum LectureRecommendReason {

    SIMILAR_TO_PURCHASED("구매한 강의와 비슷함"),
    SIMILAR_TO_CART("장바구니에 담은 강의와 비슷함"),
    MATCHES_INTEREST("입력한 관심사와 맞음"),
    POPULAR("맞춤 후보가 모자라 인기순으로 채움");

    private final String description;

    LectureRecommendReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /**
     * LLM 없이 보여 줄 이유 문장.
     *
     * @param basisTitle 가장 가까운 신호 강의 제목(구매·장바구니 이유일 때). 없으면 제목 없이 쓴다
     */
    public String sentence(@Nullable String basisTitle) {
        return switch (this) {
            case SIMILAR_TO_PURCHASED -> basisTitle == null ? "구매하신 강의와 비슷해요."
                    : "구매하신 「" + basisTitle + "」 강의와 비슷해요.";
            case SIMILAR_TO_CART -> basisTitle == null ? "장바구니에 담은 강의와 비슷해요."
                    : "장바구니에 담은 「" + basisTitle + "」 강의와 비슷해요.";
            case MATCHES_INTEREST -> "입력하신 관심사와 맞는 강의예요.";
            case POPULAR -> "요즘 많이 듣는 강의예요.";
        };
    }
}
