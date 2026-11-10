package com.hapbang.auxiliary.search.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.support.SearchElasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.elasticsearch.synonyms.SynonymRuleRead;

/** 검색 index 준비: 동의어 묶음 맞추기, index·alias 만들기, 설정이 바뀐 경우, ES 장애(#771). nori ES 컨테이너로 돈다 */
class InfluencerIndexSetupTest {

    private final ElasticsearchClient client = SearchElasticsearch.client();

    private final InfluencerIndexDefinition definition = InfluencerIndexDefinition.load();

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
    }

    @Test
    @DisplayName("처음이면 동의어 묶음을 올리고, 지금 설정의 index를 만들어 alias를 붙인다")
    void createsIndexAliasAndSynonyms() throws Exception {
        new InfluencerIndexSetup(client, definition).ensureReady();

        assertThat(client.indices().getAlias(a -> a.name(InfluencerIndexDefinition.ALIAS)).aliases().keySet())
                .containsExactly(definition.indexName());
        assertThat(synonyms()).hasSize(10).contains("먹방, mukbang");
    }

    @Test
    @DisplayName("색인 분석기는 사용자 사전으로 단어를 지키고, 검색 분석기는 동의어로 펼친다")
    void analyzers() throws Exception {
        new InfluencerIndexSetup(client, definition).ensureReady();

        assertThat(analyze("ko_index", "치지직에서 롤 방송하는 스트리머")).contains("치지직", "롤", "방송", "스트리머");
        assertThat(analyze("ko_search", "롤")).contains("롤", "리그오브레전드", "lol");
        assertThat(analyze("ko_search", "배그")).contains("배그", "배틀그라운드", "pubg");
    }

    @Test
    @DisplayName("pod 둘이 차례로 준비해도 index는 하나이고 오류가 없다")
    void idempotent() throws Exception {
        new InfluencerIndexSetup(client, definition).ensureReady();
        new InfluencerIndexSetup(client, definition).ensureReady();

        assertThat(client.indices().get(g -> g.index(InfluencerIndexDefinition.INDEX_PREFIX + "*")).indices())
                .containsOnlyKeys(definition.indexName());
    }

    @Test
    @DisplayName("ES의 동의어 묶음이 레포 파일과 다르면 묶음 전체를 바꾼다")
    void replacesDifferentSynonyms() throws Exception {
        client.synonyms().putSynonym(p -> p.id(InfluencerIndexDefinition.SYNONYMS_SET)
                .synonymsSet(s -> s.id("old").synonyms("스타, 스타크래프트")));

        new InfluencerIndexSetup(client, definition).ensureReady();

        assertThat(synonyms()).hasSize(10).doesNotContain("스타, 스타크래프트");
    }

    @Test
    @DisplayName("alias가 다른 설정의 index를 가리키면 새 index를 만들지 않고 그대로 쓴다(재색인은 #773)")
    void keepsOutdatedIndex() throws Exception {
        String old = InfluencerIndexDefinition.INDEX_PREFIX + "old000000000";
        client.indices().create(c -> c.index(old).withJson(new StringReader("{\"settings\":{\"number_of_replicas\":0}}"))
                .aliases(InfluencerIndexDefinition.ALIAS, a -> a.isWriteIndex(true)));

        new InfluencerIndexSetup(client, definition).ensureReady();

        assertThat(client.indices().getAlias(a -> a.name(InfluencerIndexDefinition.ALIAS)).aliases().keySet())
                .containsExactly(old);
        assertThat(client.indices().exists(e -> e.index(definition.indexName())).value()).isFalse();
    }

    @Test
    @DisplayName("ES에 닿지 못하면 예외를 던지고, 다음 호출에서 다시 시도한다")
    void failsWhenElasticsearchDown() {
        InfluencerIndexSetup setup = new InfluencerIndexSetup(SearchElasticsearch.client("http://127.0.0.1:9"), definition);

        assertThatThrownBy(setup::ensureReady).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(setup::ensureReady).isInstanceOf(IllegalStateException.class);
    }

    private List<String> synonyms() throws Exception {
        return client.synonyms().getSynonym(g -> g.id(InfluencerIndexDefinition.SYNONYMS_SET)).synonymsSet().stream()
                .map(SynonymRuleRead::synonyms).toList();
    }

    private List<String> analyze(String analyzer, String text) throws Exception {
        return client.indices().analyze(a -> a.index(InfluencerIndexDefinition.ALIAS).analyzer(analyzer).text(text))
                .tokens().stream().map(AnalyzeToken::token).toList();
    }
}
