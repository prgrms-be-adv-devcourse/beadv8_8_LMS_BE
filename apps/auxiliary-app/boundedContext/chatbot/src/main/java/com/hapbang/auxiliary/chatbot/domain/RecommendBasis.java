package com.hapbang.auxiliary.chatbot.domain;

/**
 * 추천 후보의 근거 (#720). 강의·인플루언서의 위젯·버튼·Tool 응답이 같은 값을 쓴다.
 * 화면은 {@link #POPULAR_TEMPORARY}일 때만 "잠시 뒤 다시 시도" 안내와 버튼을 보여 준다.
 * LLM 이유 문장 실패({@code fallback})와는 다른 사실이라 따로 둔다.
 */
public enum RecommendBasis {

    PERSONALIZED("맞춤 후보가 하나라도 있다. 모자란 자리는 인기순으로 채웠을 수 있다"),
    POPULAR("맞춤으로 고를 재료가 없어 인기순이다(신호 없음, 신호가 아직 색인 전, 맞춤 후보가 모두 제외됨, 모델·ES를 끈 실행). "
            + "다시 시도해도 같다"),
    POPULAR_TEMPORARY("검색(임베딩·ES)이 잠시 실패해 인기순으로 대신 답했다. 다시 시도하면 맞춤이 나올 수 있다");

    private final String description;

    RecommendBasis(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
