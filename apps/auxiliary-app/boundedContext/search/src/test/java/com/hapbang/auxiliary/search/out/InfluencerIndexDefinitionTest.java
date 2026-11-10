package com.hapbang.auxiliary.search.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 인플루언서 검색 index 정의: 레포 파일 읽기, 설정 해시와 index 이름(#771) */
class InfluencerIndexDefinitionTest {

    private static final String TEMPLATE = """
            {"settings":{"analysis":{"tokenizer":{"t":{"user_dictionary_rules":"__USER_DICTIONARY__"}},
             "filter":{"s":{"synonyms_set":"__SYNONYMS_SET__"}}}}}""";

    @Test
    @DisplayName("레포 파일로 index 이름(search-influencers-해시 12자)과 본문을 만들고, 사용자 사전과 동의어 묶음 이름을 채운다")
    void loadsRepositoryFiles() {
        InfluencerIndexDefinition definition = InfluencerIndexDefinition.load();

        assertThat(definition.indexName()).matches("search-influencers-[0-9a-f]{12}");
        assertThat(definition.indexBody())
                .contains("\"치지직\"", "\"배그\"", "\"" + InfluencerIndexDefinition.SYNONYMS_SET + "\"")
                .doesNotContain("__USER_DICTIONARY__", "__SYNONYMS_SET__");
        assertThat(definition.synonymRules()).hasSize(10).contains("롤, 리그 오브 레전드, 리그오브레전드, LoL");
    }

    @Test
    @DisplayName("동의어만 바뀌면 index 이름이 같다(재색인 없음). 사용자 사전이 바뀌면 이름이 바뀐다")
    void hashExcludesSynonyms() {
        InfluencerIndexDefinition base = InfluencerIndexDefinition.of(TEMPLATE, List.of("합방"), List.of("롤, LoL"));

        assertThat(InfluencerIndexDefinition.of(TEMPLATE, List.of("합방"), List.of("배그, PUBG")).indexName())
                .isEqualTo(base.indexName());
        assertThat(InfluencerIndexDefinition.of(TEMPLATE, List.of("합방", "치지직"), List.of("롤, LoL")).indexName())
                .isNotEqualTo(base.indexName());
    }

    @Test
    @DisplayName("사용자 사전에 공백·따옴표가 든 줄이 있으면 거절한다(한 단어 규칙만)")
    void rejectsMultiWordDictionary() {
        assertThatThrownBy(() -> InfluencerIndexDefinition.of(TEMPLATE, List.of("리그 오브 레전드"), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InfluencerIndexDefinition.of(TEMPLATE, List.of("합\"방"), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("파일에서 빈 줄과 #으로 시작하는 줄을 빼고 앞뒤 공백을 지운다")
    void lines() {
        assertThat(InfluencerIndexDefinition.lines("# 설명\n\n  합방  \r\n치지직\n"))
                .containsExactly("합방", "치지직");
    }
}
