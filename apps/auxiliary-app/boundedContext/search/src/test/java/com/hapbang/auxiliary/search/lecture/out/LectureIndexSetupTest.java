package com.hapbang.auxiliary.search.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.out.IndexDefinition;
import com.hapbang.auxiliary.search.common.out.IndexNotReadyException;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;

/** 강의 검색 index 정의와 준비(#882): 레포 파일, 실제 ES가 mapping을 받는지, 인플루언서 index와 섞이지 않는지. nori ES 컨테이너로 돈다 */
class LectureIndexSetupTest {

    private final ElasticsearchClient client = SearchElasticsearch.client();

    private final LectureIndexSetup setup = new LectureIndexSetup(client);

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
    }

    @Test
    @DisplayName("레포 파일로 index 이름(search-lectures-해시 12자)·alias를 만들고 사용자 사전·동의어 묶음 이름을 채운다")
    void loadsRepositoryFiles() {
        IndexDefinition definition = LectureIndexDefinition.load();

        assertThat(definition.alias()).isEqualTo("search-lectures");
        assertThat(definition.indexName()).matches("search-lectures-[0-9a-f]{12}");
        assertThat(definition.indexBody())
                .contains("\"치지직\"", "\"" + IndexDefinition.SYNONYMS_SET + "\"")
                .doesNotContain("__USER_DICTIONARY__", "__SYNONYMS_SET__");
        assertThat(definition.synonymRules()).isEqualTo(InfluencerIndexDefinition.load().synonymRules());
    }

    @Test
    @DisplayName("강의·인플루언서는 서로의 index를 자기 것으로 보지 않는다(남은 index로 지우지 않게)")
    void ownsOnlyItsIndices() {
        IndexDefinition lectures = LectureIndexDefinition.load();
        IndexDefinition influencers = InfluencerIndexDefinition.load();

        assertThat(lectures.ownsIndex(lectures.indexName())).isTrue();
        assertThat(lectures.ownsIndex(influencers.indexName())).isFalse();
        assertThat(influencers.ownsIndex(lectures.indexName())).isFalse();
    }

    @Test
    @DisplayName("alias가 없으면 만들지 않은 채 IndexNotReadyException이다. 처음 적재가 alias를 붙이면 준비된다")
    void notReadyWithoutAlias() {
        assertThatThrownBy(setup::ensureReady).isInstanceOf(IndexNotReadyException.class);
        assertThat(setup.state().indices()).isEmpty();

        SearchElasticsearch.createLectureIndex(client);

        setup.ensureReady();
        assertThat(setup.state().indices()).containsExactly(LectureIndexDefinition.load().indexName());
    }

    @Test
    @DisplayName("ES가 강의 mapping을 받고, 강의 글 칸은 사용자 사전·동의어 분석기를 쓴다")
    void analyzers() throws Exception {
        String index = SearchElasticsearch.createLectureIndex(client);

        assertThat(analyze(index, "contentTitles", "치지직 방송 세팅하기")).contains("치지직", "방송", "세팅");
        assertThat(searchAnalyze(index, "롤")).contains("롤", "리그오브레전드", "lol");
    }

    @Test
    @DisplayName("강의 index를 만들고 지워도 인플루언서 index·alias는 그대로다")
    void independentOfInfluencers() throws Exception {
        String influencer = SearchElasticsearch.createIndex(client);
        String lecture = SearchElasticsearch.createLectureIndex(client);

        setup.deleteIndices(List.of(lecture));

        assertThat(client.indices().getAlias(g -> g.name(InfluencerIndexDefinition.ALIAS)).aliases().keySet())
                .isEqualTo(Set.of(influencer));
    }

    // 색인 분석기(그 칸의 analyzer)
    private List<String> analyze(String index, String field, String text) throws Exception {
        return client.indices().analyze(a -> a.index(index).field(field).text(text)).tokens().stream()
                .map(AnalyzeToken::token).toList();
    }

    // 검색 분석기(동의어 포함)
    private List<String> searchAnalyze(String index, String text) throws Exception {
        return client.indices().analyze(a -> a.index(index).analyzer("ko_search").text(text)).tokens().stream()
                .map(AnalyzeToken::token).toList();
    }
}
