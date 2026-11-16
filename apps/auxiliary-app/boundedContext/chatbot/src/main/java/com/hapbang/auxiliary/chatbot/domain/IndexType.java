package com.hapbang.auxiliary.chatbot.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * {@code chatbot.index_task.index_type}: 색인 요청의 대상 ES index (#717, #718). DB에는 이름({@link #name()})을 적는다.
 * 실제 index 이름은 공급자·차원이 들어간 설정값이다({@code hapbang.chatbot.index.*-name}, {@code ChatbotIndexProperties#nameOf}).
 */
@Getter
@RequiredArgsConstructor
public enum IndexType {

    /** #718 */
    LECTURE("강의 추천 index"),
    /** #719 */
    INFLUENCER("인플루언서 추천 index");

    private final String description;
}
