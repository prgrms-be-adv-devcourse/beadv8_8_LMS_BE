package com.hapbang.auxiliary.search.influencer.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.out.IndexDefinition;

/** 인플루언서 검색 index 정의: 레포 파일 읽기(#771), 운영 index와 같은 설정 해시(#868) */
class InfluencerIndexDefinitionTest {

    /**
     * 운영 index 이름(#895 배포 뒤. 그 전은 {@code search-influencers-d656dfa35eee}). 사용자 사전·{@code influencers/index.json}·동의어 묶음
     * 이름을 바꾸면 이 값이 바뀌고 배포 때 재색인이 한 번 돈다. 그때는 PR 본문 "병합 전 확인"에 적고 이 값을 함께 고친다
     */
    private static final String PRODUCTION_INDEX = "search-influencers-df33405ee9ab";

    @Test
    @DisplayName("레포 파일로 index 이름(search-influencers-해시 12자)과 본문을 만들고, 사용자 사전과 동의어 묶음 이름을 채운다")
    void loadsRepositoryFiles() {
        IndexDefinition definition = InfluencerIndexDefinition.load();

        assertThat(definition.indexName()).matches("search-influencers-[0-9a-f]{12}");
        assertThat(definition.indexBody())
                .contains("\"치지직\"", "\"배그\"", "\"" + IndexDefinition.SYNONYMS_SET + "\"")
                .doesNotContain("__USER_DICTIONARY__", "__SYNONYMS_SET__");
        assertThat(definition.synonymRules()).hasSize(10).contains("롤, 리그 오브 레전드, 리그오브레전드, LoL");
    }

    @Test
    @DisplayName("설정 해시가 운영 index와 같다. 사전·동의어 파일을 공통 폴더로 옮겨도 재색인하지 않는다(#868)")
    void sameHashAsProduction() {
        IndexDefinition definition = InfluencerIndexDefinition.load();

        assertThat(definition.indexName()).isEqualTo(PRODUCTION_INDEX);
        assertThat(definition.alias()).isEqualTo(InfluencerIndexDefinition.ALIAS);
        assertThat(definition.indexPrefix()).isEqualTo(InfluencerIndexDefinition.INDEX_PREFIX);
    }
}
