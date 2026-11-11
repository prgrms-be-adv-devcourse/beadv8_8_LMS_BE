package com.hapbang.auxiliary.search.common.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 검색 index 정의(공통, #868): 설정 해시와 index 이름, 사용자 사전 규칙, 파일 줄 읽기(#771) */
class IndexDefinitionTest {

    private static final String TEMPLATE = """
            {"settings":{"analysis":{"tokenizer":{"t":{"user_dictionary_rules":"__USER_DICTIONARY__"}},
             "filter":{"s":{"synonyms_set":"__SYNONYMS_SET__"}}}}}""";

    @Test
    @DisplayName("대상 이름으로 alias(search-대상)와 index 이름(search-대상-해시 12자)을 만든다")
    void namesFromTarget() {
        IndexDefinition definition = IndexDefinition.of("lectures", TEMPLATE, List.of("합방"), List.of());

        assertThat(definition.alias()).isEqualTo("search-lectures").isEqualTo(IndexDefinition.aliasOf("lectures"));
        assertThat(definition.indexPrefix()).isEqualTo("search-lectures-");
        assertThat(definition.indexName()).matches("search-lectures-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("동의어만 바뀌면 index 이름이 같다(재색인 없음). 사용자 사전이 바뀌면 이름이 바뀐다")
    void hashExcludesSynonyms() {
        IndexDefinition base = IndexDefinition.of("influencers", TEMPLATE, List.of("합방"), List.of("롤, LoL"));

        assertThat(IndexDefinition.of("influencers", TEMPLATE, List.of("합방"), List.of("배그, PUBG")).indexName())
                .isEqualTo(base.indexName());
        assertThat(IndexDefinition.of("influencers", TEMPLATE, List.of("합방", "치지직"), List.of("롤, LoL")).indexName())
                .isNotEqualTo(base.indexName());
    }

    @Test
    @DisplayName("사용자 사전에 공백·따옴표가 든 줄이 있으면 거절한다(한 단어 규칙만)")
    void rejectsMultiWordDictionary() {
        assertThatThrownBy(() -> IndexDefinition.of("influencers", TEMPLATE, List.of("리그 오브 레전드"), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IndexDefinition.of("influencers", TEMPLATE, List.of("합\"방"), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("파일에서 빈 줄과 #으로 시작하는 줄을 빼고 앞뒤 공백을 지운다")
    void lines() {
        assertThat(IndexDefinition.lines("# 설명\n\n  합방  \r\n치지직\n"))
                .containsExactly("합방", "치지직");
    }
}
