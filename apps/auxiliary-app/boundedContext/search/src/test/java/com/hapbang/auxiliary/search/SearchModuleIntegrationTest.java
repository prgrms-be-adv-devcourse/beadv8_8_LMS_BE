package com.hapbang.auxiliary.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;
import com.hapbang.auxiliary.search.support.SearchElasticsearchTestcontainersConfig;
import com.hapbang.testsupport.IntegrationTest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;

/**
 * 검색 모듈만으로 컨텍스트가 뜨고, 모듈 SQL이 {@code search} schema를 만들고, 검색 전용 client가 nori를 설치한 ES 9.5.5에 붙는지 본다(#771).
 * 팀 공통 {@link IntegrationTest}가 이 모듈 프로젝트의 클래스패스(검색·global·shared)만으로 띄운다.
 */
@IntegrationTest
@Import(SearchElasticsearchTestcontainersConfig.class)
class SearchModuleIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(SearchElasticsearchConfig.CLIENT)
    private ElasticsearchClient searchClient;

    @Autowired
    private ElasticsearchClient defaultClient;

    @Test
    @DisplayName("모듈 SQL이 search schema를 만든다")
    void schema() {
        assertThat(jdbcTemplate.queryForList(
                "select schema_name from information_schema.schemata where schema_name = 'search'", String.class))
                .containsExactly("search");
    }

    @Test
    @DisplayName("검색 client는 ES 9.5.5에 붙고, nori로 한국어를 형태소로 나눈다")
    void connectsWithNori() throws Exception {
        assertThat(searchClient.info().version().number()).isEqualTo(SearchElasticsearchTestcontainersConfig.ES_VERSION);

        List<String> tokens = searchClient.indices()
                .analyze(a -> a.tokenizer(t -> t.name("nori_tokenizer")).text("메이플스토리 합방"))
                .tokens().stream().map(AnalyzeToken::token).toList();

        assertThat(tokens).contains("메이플", "스토리", "합방");
    }

    @Test
    @DisplayName("검색 client는 이름으로만 받고, 이름 없이 받는 client는 챗봇이 쓰는 Boot 자동 설정 client다")
    void separateFromDefaultClient() {
        assertThat(searchClient).isNotSameAs(defaultClient);
    }
}
