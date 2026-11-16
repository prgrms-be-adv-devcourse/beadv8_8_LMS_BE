package com.hapbang.auxiliary.search.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.out.IndexDefinition;

/** 강의 검색 index 정의: 운영 index와 같은 설정 해시(인플루언서 {@code InfluencerIndexDefinitionTest}와 같은 고정) */
class LectureIndexDefinitionTest {

    /**
     * 운영 index 이름(#973 배포 뒤. 그 전은 #972의 {@code search-lectures-d6d024137a51}). 사용자 사전·{@code lectures/index.json}·동의어 묶음
     * 이름을 바꾸면 이 값이 바뀌고 배포 때 강의 index 재색인이 한 번 돈다. 그때는 PR 본문 "병합 전 확인"에 적고 이 값을 함께 고친다
     */
    private static final String PRODUCTION_INDEX = "search-lectures-5f89a563885d";

    @Test
    @DisplayName("설정 해시가 운영 index와 같다. 바뀌면 배포 때 재색인이 돈다는 뜻이라 PR에 적는다")
    void sameHashAsProduction() {
        IndexDefinition definition = LectureIndexDefinition.load();

        assertThat(definition.indexName()).isEqualTo(PRODUCTION_INDEX);
        assertThat(definition.alias()).isEqualTo(LectureIndexDefinition.ALIAS);
        assertThat(definition.indexPrefix()).isEqualTo(LectureIndexDefinition.INDEX_PREFIX);
    }
}
