package com.hapbang.auxiliary.search.common.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.app.IndexCatchUp.CatchUp;
import com.hapbang.auxiliary.search.common.app.Reindexer.ReindexReason;
import com.hapbang.auxiliary.search.common.app.Reindexer.ReindexResult;
import com.hapbang.auxiliary.search.common.out.DocumentWrites;
import com.hapbang.auxiliary.search.common.out.IndexDefinition;
import com.hapbang.auxiliary.search.common.out.IndexSetup;
import com.hapbang.auxiliary.search.common.out.SynonymsSetup;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 공통 재색인 흐름을 가짜 부품으로 확인한다(#868). 인플루언서가 아닌 시험용 대상({@value #TARGET})으로 돌려, 흐름이 대상 코드에 기대지 않는지 본다.
 * 인플루언서 부품을 끼운 흐름의 시나리오(롤링 배포, 교체 시간 초과, 재색인 중 검색 등)는 {@code InfluencerReindexerTest}가 본다.
 */
class ReindexerTest {

    private static final String TARGET = "reindexer-test";
    private static final String METRIC_TARGET = "test";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 10, 0);

    /** 동의어 묶음을 쓰는 가장 작은 index 설정(공통 사전·동의어 자리표시자를 채운다) */
    private static final String TEMPLATE = """
            {"settings":{"number_of_shards":1,"number_of_replicas":0,"analysis":{
              "tokenizer":{"t":{"type":"nori_tokenizer","user_dictionary_rules":"__USER_DICTIONARY__"}},
              "filter":{"s":{"type":"synonym_graph","synonyms_set":"__SYNONYMS_SET__","updateable":true}},
              "analyzer":{"a":{"type":"custom","tokenizer":"t"},"q":{"type":"custom","tokenizer":"t","filter":["s"]}}}},
             "mappings":{"dynamic":"strict","properties":{
              "hidden":{"type":"boolean"},"title":{"type":"text","analyzer":"a","search_analyzer":"q"}}}}""";

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final IndexDefinition definition = IndexDefinition.of(TARGET, TEMPLATE, List.of("합방"), List.of("롤, LoL"));
    private final IndexSetup indexSetup = new IndexSetup(client, definition,
            new SynonymsSetup(client, definition.synonymRules()));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FakeLoader loader = new FakeLoader();
    private final FakeCatchUp catchUp = new FakeCatchUp();
    private final Reindexer reindexer = new Reindexer(METRIC_TARGET, indexSetup,
            new DocumentWrites(client, definition.alias()), loader, catchUp, null, SearchJob.REINDEX, registry,
            Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE), Duration.ofMillis(10), Duration.ofMillis(500),
            Instant.now().plus(Duration.ofHours(1)));

    @BeforeEach
    void reset() throws Exception {
        deleteTestIndices();
        SearchElasticsearch.reset(client);
    }

    /** 시험용 index가 동의어 묶음을 쓰고 있으면 다음 테스트가 묶음을 지우지 못한다 */
    @AfterEach
    void cleanUp() throws Exception {
        deleteTestIndices();
    }

    @Test
    @DisplayName("alias가 없으면 부품으로 채우고 alias를 붙인 뒤 T − 부품의 겹쳐 읽기부터 따라잡는다. 지표에 대상 태그가 붙는다")
    void firstLoad() {
        loader.visible = 2;
        loader.hidden = 1;

        ReindexResult result = reindexer.reindexIfNeeded();

        assertThat(result.reason()).isEqualTo(ReindexReason.FIRST_LOAD);
        assertThat(result.done()).isTrue();
        assertThat(result.loaded().visible()).isEqualTo(2);
        assertThat(result.loaded().hidden()).isEqualTo(1);
        assertThat(result.catchUp()).isEqualTo(CatchUp.CAUGHT_UP);
        assertThat(loader.indices).containsExactly(definition.indexName());
        assertThat(indexSetup.state().aliasTargets()).containsExactly(definition.indexName());
        assertThat(catchUp.calls).containsExactly(NOW.minus(FakeCatchUp.OVERLAP));
        assertThat(registry.get("hapbang.search.reindex.duration").tag("target", METRIC_TARGET)
                .tag("reason", "first_load").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("hapbang.search.reindex.documents").tag("target", METRIC_TARGET)
                .tag("reason", "first_load").counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("이름이 이 대상 + \"-…\"인 다른 대상의 index는 남은 index로 보지 않고 지우지 않는다(#868 리뷰)")
    void keepsOtherTargetIndex() throws Exception {
        String other = definition.indexPrefix() + "reviews-0123456789ab";
        client.indices().create(c -> c.index(other));

        reindexer.reindexIfNeeded();

        assertThat(client.indices().exists(e -> e.index(other)).value()).isTrue();
        assertThat(indexSetup.state().indices()).containsExactly(definition.indexName());
    }

    @Test
    @DisplayName("설정이 같으면 아무것도 하지 않는다")
    void upToDate() {
        loader.visible = 1;
        reindexer.reindexIfNeeded();

        ReindexResult again = reindexer.reindexIfNeeded();

        assertThat(again.done()).isFalse();
        assertThat(again.failed()).isFalse();
        assertThat(loader.indices).hasSize(1);
        assertThat(catchUp.calls).hasSize(1);
    }

    @Test
    @DisplayName("부품이 말한 보이는 문서 수와 새 index의 수가 다르면 alias를 붙이지 않고 새 index를 지운다")
    void countMismatch() {
        loader.visible = 2;
        loader.reportedVisible = 3L;

        ReindexResult result = reindexer.reindexIfNeeded();

        assertThat(result.failed()).isTrue();
        assertThat(indexSetup.state().aliasTargets()).isEmpty();
        assertThat(indexSetup.state().indices()).isEmpty();
        assertThat(catchUp.calls).isEmpty();
        assertThat(registry.get("hapbang.search.reindex.duration").tag("target", METRIC_TARGET)
                .tag("outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("따라잡기가 BUSY면 잠시 기다렸다 다시 부른다")
    void retriesBusyCatchUp() {
        loader.visible = 1;
        catchUp.results.add(CatchUp.BUSY);
        catchUp.results.add(CatchUp.BUSY);

        ReindexResult result = reindexer.reindexIfNeeded();

        assertThat(result.catchUp()).isEqualTo(CatchUp.CAUGHT_UP);
        assertThat(catchUp.calls).hasSize(3);
    }

    private void deleteTestIndices() throws IOException {
        List<String> indices = List.copyOf(client.indices().get(g -> g.index(definition.indexPrefix() + "*")
                .allowNoIndices(true)).indices().keySet());
        if (!indices.isEmpty()) {
            client.indices().delete(d -> d.index(indices));
        }
    }

    /** 보이는 문서 visible개, 숨김 문서 hidden개를 넣고, 보이는 수로 reportedVisible(없으면 visible)을 돌려준다 */
    private final class FakeLoader implements IndexLoader {

        private final List<String> indices = new ArrayList<>();
        private int visible;
        private int hidden;
        private Long reportedVisible;

        @Override
        public LoadResult load(String index) {
            indices.add(index);
            int id = 0;
            try {
                for (int i = 0; i < visible + hidden; i++) {
                    boolean hide = i >= visible;
                    String docId = String.valueOf(++id);
                    client.index(r -> r.index(index).id(docId).document(Map.of("hidden", hide, "title", "합방 " + docId)));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            long reported = reportedVisible == null ? visible : reportedVisible;
            return new LoadResult(visible + hidden, reported, hidden, 0);
        }
    }

    /** 미리 넣은 결과를 차례로 돌려주고, 다 쓰면 CAUGHT_UP */
    private static final class FakeCatchUp implements IndexCatchUp {

        /** 인플루언서(1분)와 다른 값이라 흐름이 부품의 값을 쓰는지 보인다 */
        private static final Duration OVERLAP = Duration.ofMinutes(2);

        private final Deque<CatchUp> results = new ArrayDeque<>();
        private final List<LocalDateTime> calls = new ArrayList<>();

        @Override
        public Duration overlap() {
            return OVERLAP;
        }

        @Override
        public CatchUp runFrom(LocalDateTime from) {
            calls.add(from);
            return results.isEmpty() ? CatchUp.CAUGHT_UP : results.poll();
        }
    }
}
