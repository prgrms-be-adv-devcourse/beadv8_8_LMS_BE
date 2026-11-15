package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * 구조화 출력 응답 레코드의 스키마가 OpenAI strict 규칙(객체의 모든 필드가 required)을 지키는지 본다.
 * 지키지 않으면 OpenAI가 400으로 거절해 리랭킹·쿼리 확장이 늘 fallback이 된다(#867 시험 실행에서 발견).
 */
class StructuredOutputSchemaTest {

    @Test
    void rerankAnswerListsEveryFieldAsRequired() {
        assertThat(objectsMissingRequired(RecommendReranker.Picks.class)).isEmpty();
    }

    @Test
    void expansionAnswerListsEveryFieldAsRequired() {
        assertThat(objectsMissingRequired(QueryExpander.Expansions.class)).isEmpty();
    }

    // 스키마 안의 객체마다 properties 키와 required가 같은지 보고, 다른 객체의 properties 키를 모은다
    private static List<Object> objectsMissingRequired(Class<?> type) {
        List<Object> missing = new ArrayList<>();
        collect(new BeanOutputConverter<>(type).getJsonSchemaMap(), missing);
        return missing;
    }

    private static void collect(Object node, List<Object> missing) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("properties") instanceof Map<?, ?> properties) {
                Object required = map.get("required");
                List<?> requiredKeys = required instanceof List<?> list ? list : List.of();
                if (!requiredKeys.containsAll(properties.keySet())) {
                    missing.add(properties.keySet());
                }
            }
            map.values().forEach(value -> collect(value, missing));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> collect(value, missing));
        }
    }
}
