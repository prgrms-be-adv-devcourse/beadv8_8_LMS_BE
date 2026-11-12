package com.hapbang.auxiliary.search.common.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.common.app.Reindexer.ReindexResult;
import com.hapbang.auxiliary.search.common.out.DocumentWrites;
import com.hapbang.auxiliary.search.common.out.IndexDefinition;
import com.hapbang.auxiliary.search.common.out.IndexSetup;
import com.hapbang.auxiliary.search.common.out.SynonymsSetup;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 동의어 묶음 확인(#895, #868 R9). 실제 ES(9.5.5)에 묶음을 올려 본다. 대상이 둘이어도 거절 지표는 확인 한 곳에서 한 번만 남는다.
 */
class SynonymsCheckTest {

    /** ES가 거절하는 규칙({@code =>}가 둘) */
    private static final List<String> BROKEN_RULES = List.of("롤 => 리그 => LoL");
    private static final String METRIC = "hapbang.search.synonyms.rejected";
    /** 동의어 묶음을 쓰는 가장 작은 index 설정 */
    private static final String TEMPLATE = """
            {"settings":{"number_of_shards":1,"number_of_replicas":0,"analysis":{
              "tokenizer":{"t":{"type":"nori_tokenizer","user_dictionary_rules":"__USER_DICTIONARY__"}},
              "filter":{"s":{"type":"synonym_graph","synonyms_set":"__SYNONYMS_SET__","updateable":true}},
              "analyzer":{"q":{"type":"custom","tokenizer":"t","filter":["s"]}}}},
             "mappings":{"properties":{"hidden":{"type":"boolean"}}}}""";

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RecordingRunner runner = new RecordingRunner();

    @BeforeEach
    void reset() throws Exception {
        SearchElasticsearch.reset(client);
    }

    @Test
    @DisplayName("묶음이 레포 규칙과 같아지면 true이고 거절 지표를 남기지 않는다")
    void syncs() throws Exception {
        SynonymsCheck check = new SynonymsCheck(new SynonymsSetup(client, List.of("롤, LoL")), runner, registry);

        assertThat(check.check()).isTrue();
        assertThat(client.synonyms().getSynonym(g -> g.id(IndexDefinition.SYNONYMS_SET)).synonymsSet())
                .hasSize(1);
        assertThat(registry.find(METRIC).counter()).isNull();
    }

    @Test
    @DisplayName("ES가 묶음을 거절하면 false이고 거절 지표를 한 번 남긴다")
    void rejected() {
        SynonymsCheck check = new SynonymsCheck(new SynonymsSetup(client, BROKEN_RULES), runner, registry);

        assertThat(check.check()).isFalse();
        assertThat(registry.get(METRIC).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("대상이 둘이어도 거절 지표는 확인 한 곳에서 한 번이고, 두 대상의 재색인은 지표 없이 건너뛴다(R9)")
    void oneAlertForManyTargets() {
        SynonymsSetup shared = new SynonymsSetup(client, BROKEN_RULES);
        SynonymsCheck check = new SynonymsCheck(shared, runner, registry);
        Reindexer first = reindexer("synonyms-check-a", shared);
        Reindexer second = reindexer("synonyms-check-b", shared);

        check.check();
        ReindexResult firstResult = first.reindexIfNeeded();
        ReindexResult secondResult = second.reindexIfNeeded();

        assertThat(firstResult.failed()).isTrue();
        assertThat(secondResult.failed()).isTrue();
        assertThat(registry.get(METRIC).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 pod에서만 돌게 동의어 확인 락으로 실행한다")
    void runsUnderLock() {
        SynonymsCheck check = new SynonymsCheck(new SynonymsSetup(client, List.of("롤, LoL")), runner, registry);

        check.run();

        assertThat(runner.jobs).containsExactly(SearchJob.SYNONYMS_CHECK);
    }

    /** 대상 하나의 재색인. 동의어에서 멈추므로 채우기·따라잡기·락은 쓰지 않는다 */
    private Reindexer reindexer(String target, SynonymsSetup synonyms) {
        IndexDefinition definition = IndexDefinition.of(target, TEMPLATE, List.of("합방"), BROKEN_RULES);
        return new Reindexer(target, new IndexSetup(client, definition, synonyms),
                new DocumentWrites(client, definition.alias()), null, null, null, SearchJob.REINDEX, registry,
                Clock.systemUTC(), Duration.ofMillis(10), Duration.ofMillis(500), Instant.now());
    }

    /** 락 없이 바로 실행하고 어느 작업으로 불렸는지 남긴다 */
    private static final class RecordingRunner extends SearchJobRunner {

        private final List<SearchJob> jobs = new ArrayList<>();

        private RecordingRunner() {
            super(null);
        }

        @Override
        public boolean run(SearchJob job, Runnable task) {
            jobs.add(job);
            task.run();
            return true;
        }
    }
}
