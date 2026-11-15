package com.hapbang.auxiliary.chatbot.app;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FAQ 대화의 답.
 *
 * @param answer   답변 글
 * @param sources  근거 FAQ(Tool이 찾은 것, 겹치지 않게 처음 찾은 순서)
 * @param fallback LLM 대신 검색 결과·안내 문구로 답했으면 true
 */
public record FaqAnswer(String answer, List<FaqSource> sources, boolean fallback) {

    /** 근거 FAQ 하나 */
    public record FaqSource(String code, String question) {
    }

    /** Tool이 찾은 FAQ를 근거 목록으로 바꾼다. 같은 FAQ를 여러 번 찾았으면 한 번만 넣는다 */
    static List<FaqSource> sourcesOf(Collection<FaqHit> hits) {
        Map<String, FaqSource> sources = new LinkedHashMap<>();
        for (FaqHit hit : hits) {
            sources.putIfAbsent(hit.code(), new FaqSource(hit.code(), hit.question()));
        }
        return List.copyOf(sources.values());
    }
}
