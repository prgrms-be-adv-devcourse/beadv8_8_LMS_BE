package com.hapbang.auxiliary.search.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.support.SearchElasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.elasticsearch.synonyms.SynonymRuleRead;

/**
 * 검색 index 준비와 관리: 동의어 묶음 맞추기, alias가 없을 때(처음 적재 전), 설정이 바뀐 경우, 상태 보기, alias 원자 교체, ES 장애(#771, #773).
 * nori ES 컨테이너로 돈다
 */
class InfluencerIndexSetupTest {

    private static final String OLD = InfluencerIndexDefinition.INDEX_PREFIX + "old000000000";

    private final ElasticsearchClient client = SearchElasticsearch.client();

    private final InfluencerIndexDefinition definition = InfluencerIndexDefinition.load();

    private final InfluencerIndexSetup setup = new InfluencerIndexSetup(client, definition);

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
    }

    @Test
    @DisplayName("alias가 없으면 동의어 묶음만 올리고 index·alias를 만들지 않은 채 IndexNotReadyException이다. 처음 적재가 alias를 붙이면 준비된다")
    void notReadyWithoutAlias() throws Exception {
        assertThatThrownBy(setup::ensureReady).isInstanceOf(IndexNotReadyException.class);

        assertThat(synonyms()).hasSize(10).contains("먹방, mukbang");
        assertThat(setup.state().indices()).isEmpty();

        setup.switchAlias(List.of(), setup.createIndex());
        setup.ensureReady();
    }

    @Test
    @DisplayName("색인 분석기는 사용자 사전으로 단어를 지키고, 검색 분석기는 동의어로 펼친다")
    void analyzers() throws Exception {
        SearchElasticsearch.createIndex(client);

        assertThat(analyze("ko_index", "치지직에서 롤 방송하는 스트리머")).contains("치지직", "롤", "방송", "스트리머");
        assertThat(analyze("ko_search", "롤")).contains("롤", "리그오브레전드", "lol");
        assertThat(analyze("ko_search", "배그")).contains("배그", "배틀그라운드", "pubg");
    }

    @Test
    @DisplayName("ES의 동의어 묶음이 레포 파일과 다르면 묶음 전체를 바꾼다")
    void replacesDifferentSynonyms() throws Exception {
        client.synonyms().putSynonym(p -> p.id(InfluencerIndexDefinition.SYNONYMS_SET)
                .synonymsSet(s -> s.id("old").synonyms("스타, 스타크래프트")));

        setup.syncSynonyms();

        assertThat(synonyms()).hasSize(10).doesNotContain("스타, 스타크래프트");
    }

    @Test
    @DisplayName("한 번 맞춘 뒤 ES의 묶음이 사라져도(ES를 새로 만듦) syncSynonyms는 기억대로 넘어가고, resyncSynonyms는 다시 올린다")
    void resyncsAfterElasticsearchRecreated() throws Exception {
        setup.syncSynonyms();
        SearchElasticsearch.reset(client);

        setup.syncSynonyms();
        assertThatThrownBy(this::synonyms).isInstanceOf(co.elastic.clients.elasticsearch._types.ElasticsearchException.class);

        setup.resyncSynonyms();
        assertThat(synonyms()).hasSize(10);
    }

    @Test
    @DisplayName("ES가 동의어 규칙을 거절하면 SynonymsRejectedException이다. 문서 쓰기 준비는 지난 묶음으로 계속한다(PR #845 재리뷰)")
    void synonymsRejected() throws Exception {
        SearchElasticsearch.createIndex(client);
        String template;
        try (var in = new org.springframework.core.io.ClassPathResource("search/influencers/index.json").getInputStream()) {
            template = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        // ES 9.5.5가 400(action_request_validation_exception)으로 거절하는 규칙. "=> 롤" 같은 규칙은 받고 무시한다(직접 확인)
        InfluencerIndexDefinition broken = InfluencerIndexDefinition.of(template, List.of(), List.of("롤 => 리그 => LoL"));
        InfluencerIndexSetup brokenSetup = new InfluencerIndexSetup(client, broken);

        assertThatThrownBy(brokenSetup::resyncSynonyms).isInstanceOf(SynonymsRejectedException.class);
        brokenSetup.ensureReady();
        assertThat(synonyms()).hasSize(10);
    }

    @Test
    @DisplayName("alias가 다른 설정의 index를 가리키면 새 index를 만들지 않고 그대로 쓴다(재색인이 옮긴다)")
    void keepsOutdatedIndex() throws Exception {
        createOld();

        setup.ensureReady();

        assertThat(setup.state().aliasTargets()).containsExactly(OLD);
        assertThat(client.indices().exists(e -> e.index(definition.indexName())).value()).isFalse();
    }

    @Test
    @DisplayName("상태: alias 없음 → 지금 설정 하나만 가리킴 / 다른 설정을 가리킴, alias가 가리키지 않는 index는 남은 것")
    void state() throws Exception {
        assertThat(setup.state().aliasMissing()).isTrue();

        createOld();
        String expected = setup.createIndex();
        IndexState outdated = setup.state();
        assertThat(outdated.aliasMissing()).isFalse();
        assertThat(outdated.upToDate()).isFalse();
        assertThat(outdated.expectedIndex()).isEqualTo(definition.indexName()).isEqualTo(expected);
        assertThat(outdated.leftovers()).containsExactly(expected);

        setup.switchAlias(List.of(OLD), expected);
        IndexState upToDate = setup.state();
        assertThat(upToDate.upToDate()).isTrue();
        assertThat(upToDate.leftovers()).containsExactly(OLD);
    }

    @Test
    @DisplayName("alias를 한 요청으로 옛 index에서 새 index로 옮긴다. 옛 index가 alias에 없으면 요청 전체가 거절되고 alias는 그대로다")
    void switchAlias() throws Exception {
        createOld();
        String expected = setup.createIndex();

        assertThatThrownBy(() -> setup.switchAlias(List.of(expected), expected)).isInstanceOf(IllegalStateException.class);
        assertThat(setup.state().aliasTargets()).containsExactly(OLD);

        setup.switchAlias(List.of(OLD), expected);
        assertThat(setup.state().aliasTargets()).containsExactly(expected);
        assertThat(client.indices().getAlias(a -> a.name(InfluencerIndexDefinition.ALIAS)).aliases().get(expected)
                .aliases().get(InfluencerIndexDefinition.ALIAS).isWriteIndex()).isTrue();
    }

    @Test
    @DisplayName("이미 있는 index는 만들지 못하고, 지우면 사라진다")
    void createAndDelete() {
        String expected = setup.createIndex();
        assertThatThrownBy(setup::createIndex).isInstanceOf(IllegalStateException.class);

        setup.deleteIndices(Set.of(expected));
        setup.deleteIndices(Set.of());

        assertThat(setup.state().indices()).isEmpty();
    }

    @Test
    @DisplayName("ES에 닿지 못하면 예외를 던지고, 다음 호출에서 다시 시도한다")
    void failsWhenElasticsearchDown() {
        InfluencerIndexSetup down = new InfluencerIndexSetup(SearchElasticsearch.client("http://127.0.0.1:9"), definition);

        assertThatThrownBy(down::ensureReady).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(down::ensureReady).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(down::state).isInstanceOf(IllegalStateException.class);
    }

    /** alias가 가리키는 다른 설정의 옛 index */
    private void createOld() throws Exception {
        client.indices().create(c -> c.index(OLD).withJson(new StringReader("{\"settings\":{\"number_of_replicas\":0}}"))
                .aliases(InfluencerIndexDefinition.ALIAS, a -> a.isWriteIndex(true)));
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
