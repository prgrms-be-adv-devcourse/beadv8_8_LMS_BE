package com.hapbang.auxiliary.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.chatbot.app.ChatCards;
import com.hapbang.auxiliary.chatbot.app.ChatToolContext;
import com.hapbang.auxiliary.chatbot.app.FaqHit;
import com.hapbang.auxiliary.chatbot.app.FaqIndexer;
import com.hapbang.auxiliary.chatbot.app.FaqSearchService;
import com.hapbang.auxiliary.chatbot.app.FaqSeed;
import com.hapbang.auxiliary.chatbot.app.FaqSeedReader;
import com.hapbang.auxiliary.chatbot.app.FaqSeedSynchronizer;
import com.hapbang.auxiliary.chatbot.app.FaqSyncService;
import com.hapbang.auxiliary.chatbot.app.FaqTools;
import com.hapbang.auxiliary.chatbot.domain.Faq;
import com.hapbang.auxiliary.chatbot.domain.FaqRepository;
import com.hapbang.auxiliary.chatbot.out.FaqVectorIndex;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.testsupport.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FAQ 시드 맞추기·벡터 색인·검색·Tool을 실제 PostgreSQL·Elasticsearch(9.5.5)와 가짜 임베딩으로 확인한다(#715).
 * 가짜 임베딩은 정해 둔 글만 정해 둔 벡터로 바꾸므로 유사도를 정확히 통제한다({@link FakeEmbeddingModel}).
 */
@IntegrationTest
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
class FaqIndexingIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private FaqSyncService syncService;

    @Autowired
    private FaqIndexer indexer;

    @Autowired
    private FaqSeedSynchronizer seedSynchronizer;

    @Autowired
    private FaqSeedReader seedReader;

    @Autowired
    private FaqRepository faqRepository;

    @Autowired
    private FaqVectorIndex vectorIndex;

    @Autowired
    private FaqSearchService searchService;

    @Autowired
    private FaqTools faqTools;

    @Autowired
    private FakeEmbeddingModel embeddingModel;

    private List<FaqSeed> seeds;

    @BeforeEach
    void setUp() throws Exception {
        embeddingModel.reset();
        deleteIndex();
        seeds = seedReader.read();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("시드 53건을 DB에 넣고 모두 색인한다. index는 양자화 없는 hnsw·코사인·지정 차원이고 metadata는 keyword다")
    void syncAllIndexesEveryFaq() throws Exception {
        syncService.syncAll();

        assertThat(faqRepository.count()).isEqualTo(53);
        assertThat(faqRepository.findNeedsIndexing()).isEmpty();
        assertThat(faqRepository.findAll()).allSatisfy(faq -> assertThat(faq.getIndexedAt()).isNotNull());
        assertThat(vectorIndex.count()).isEqualTo(53);
        assertThat(vectorIndex.codes()).hasSize(53).contains("FAQ-001", "FAQ-952");

        JsonNode properties = JSON.readTree(get("/" + vectorIndex.indexName() + "/_mapping"))
                .get(vectorIndex.indexName()).get("mappings").get("properties");
        JsonNode embedding = properties.get("embedding");
        assertThat(embedding.get("type").asString()).isEqualTo("dense_vector");
        assertThat(embedding.get("dims").asInt()).isEqualTo(FakeEmbeddingModel.DIMENSIONS);
        assertThat(embedding.get("similarity").asString()).isEqualTo("cosine");
        assertThat(embedding.get("index_options").get("type").asString()).isEqualTo("hnsw");
        assertThat(properties.get("metadata").get("properties").get("code").get("type").asString()).isEqualTo("keyword");
    }

    @Test
    @DisplayName("다시 맞추면 바뀐 것이 없어 다시 임베딩하지 않는다(멱등)")
    void syncIsIdempotent() {
        syncService.syncAll();
        int embedded = embeddingModel.embeddedTexts();

        FaqSeedSynchronizer.SeedResult again = seedSynchronizer.sync(seeds);
        FaqIndexer.IndexResult index = indexer.sync();

        assertThat(again).isEqualTo(new FaqSeedSynchronizer.SeedResult(0, 0, List.of()));
        assertThat(index.indexed()).isZero();
        assertThat(index.rebuilt()).isFalse();
        assertThat(embeddingModel.embeddedTexts()).isEqualTo(embedded);
    }

    @Test
    @DisplayName("시드 내용이 바뀐 FAQ만 DB를 고치고 다시 임베딩한다")
    void changedFaqIsReindexed() {
        syncService.syncAll();
        List<FaqSeed> changed = new ArrayList<>(seeds);
        FaqSeed first = changed.getFirst();
        changed.set(0, new FaqSeed(first.code(), first.category(), first.question(), first.answer() + " 바뀐 문장.",
                first.policyRef()));

        FaqSeedSynchronizer.SeedResult result = seedSynchronizer.sync(changed);
        assertThat(faqRepository.findNeedsIndexing()).extracting(Faq::getCode).containsExactly(first.code());
        FaqIndexer.IndexResult index = indexer.sync();

        assertThat(result).isEqualTo(new FaqSeedSynchronizer.SeedResult(0, 1, List.of()));
        assertThat(index.indexed()).isEqualTo(1);
        assertThat(faqRepository.findNeedsIndexing()).isEmpty();
    }

    @Test
    @DisplayName("시드에서 빠진 FAQ는 DB와 색인에서 지운다")
    void removedFaqIsDeleted() {
        syncService.syncAll();
        List<FaqSeed> withoutFirst = seeds.subList(1, seeds.size());

        FaqSeedSynchronizer.SeedResult result = seedSynchronizer.sync(withoutFirst);
        FaqIndexer.IndexResult index = indexer.sync();

        assertThat(result.removedCodes()).containsExactly("FAQ-001");
        assertThat(index.removed()).isEqualTo(1);
        assertThat(faqRepository.count()).isEqualTo(52);
        assertThat(vectorIndex.codes()).hasSize(52).doesNotContain("FAQ-001");
    }

    @Test
    @DisplayName("임베딩이 실패하면 색인 해시를 남기지 않고, 모델이 돌아온 뒤 다음 점검에서 다시 색인한다")
    void failedIndexingIsRetried() {
        seedSynchronizer.sync(seeds);
        embeddingModel.failWith(new IllegalStateException("임베딩 모델 장애"));

        assertThatThrownBy(() -> indexer.sync()).hasMessage("임베딩 모델 장애");
        assertThat(faqRepository.findNeedsIndexing()).hasSize(53);

        embeddingModel.recover();
        FaqIndexer.IndexResult retry = indexer.sync();

        assertThat(retry.indexed()).isEqualTo(53);
        assertThat(faqRepository.findNeedsIndexing()).isEmpty();
        assertThat(vectorIndex.count()).isEqualTo(53);
    }

    @Test
    @DisplayName("index가 없어지면(ES 재설치·모델 변경으로 새 index) 색인 해시를 지우고 모두 다시 색인한다")
    void missingIndexIsRebuilt() throws Exception {
        syncService.syncAll();
        deleteIndex();

        FaqIndexer.IndexResult result = indexer.sync();

        assertThat(result.rebuilt()).isTrue();
        assertThat(result.indexed()).isEqualTo(53);
        assertThat(vectorIndex.count()).isEqualTo(53);
    }

    @Test
    @DisplayName("기준값(코사인 0.8) 경계: 0.81은 찾고 0.79는 버린다. 점수는 코사인 값 그대로다")
    void similarityThresholdBoundary() {
        embeddingModel.put(question("FAQ-201"), FakeEmbeddingModel.axis(0));
        embeddingModel.put(question("FAQ-301"), FakeEmbeddingModel.axis(1));
        embeddingModel.put(FakeEmbeddingModel.query("경계 위"), FakeEmbeddingModel.withCosine(0, 1, 0.81));
        embeddingModel.put(FakeEmbeddingModel.query("경계 아래"), FakeEmbeddingModel.withCosine(0, 1, 0.79));
        syncService.syncAll();

        List<FaqHit> above = searchService.search("경계 위");
        List<FaqHit> below = searchService.search("경계 아래");

        // FAQ-301과의 코사인은 각각 약 0.59, 0.61이라 둘 다 기준값 아래다
        assertThat(above).extracting(FaqHit::code).containsExactly("FAQ-201");
        assertThat(above.getFirst().score()).isCloseTo(0.81, within(0.001));
        assertThat(above.getFirst().answer()).isEqualTo(seed("FAQ-201").answer());
        assertThat(below).isEmpty();
        // 표에 없는 질문은 어떤 문서와도 직교한다
        assertThat(searchService.search("표에 없는 질문")).isEmpty();
    }

    @Test
    @DisplayName("검색은 유사도 높은 순으로 최대 3개다")
    void searchReturnsTopK() {
        embeddingModel.put(question("FAQ-201"), FakeEmbeddingModel.axis(0));
        embeddingModel.put(question("FAQ-202"), FakeEmbeddingModel.withCosine(0, 1, 0.95));
        embeddingModel.put(question("FAQ-203"), FakeEmbeddingModel.withCosine(0, 2, 0.9));
        embeddingModel.put(question("FAQ-204"), FakeEmbeddingModel.withCosine(0, 3, 0.85));
        embeddingModel.put(FakeEmbeddingModel.query("HP 질문"), FakeEmbeddingModel.axis(0));
        syncService.syncAll();

        assertThat(searchService.search("HP 질문")).extracting(FaqHit::code)
                .containsExactly("FAQ-201", "FAQ-202", "FAQ-203");
    }

    @Test
    @DisplayName("searchFaq Tool: 이름·설명·인자 schema, 결과 JSON(code·question·answer·score), ToolContext 목록에 근거를 담는다")
    void searchFaqTool() {
        embeddingModel.put(question("FAQ-201"), FakeEmbeddingModel.axis(0));
        embeddingModel.put(FakeEmbeddingModel.query("HP 충전 방법"), FakeEmbeddingModel.axis(0));
        syncService.syncAll();
        List<FaqHit> sources = new CopyOnWriteArrayList<>();

        ToolCallback[] callbacks = ToolCallbacks.from(faqTools);
        String result = callbacks[0].call("{\"question\":\"HP 충전 방법\"}", new ToolContext(ChatToolContext.of(1L, sources, new ChatCards.Collector())));

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("searchFaq");
        assertThat(callbacks[0].getToolDefinition().description()).contains("HP 충전", "출연", "강의 구매");
        assertThat(callbacks[0].getToolDefinition().inputSchema()).contains("\"question\"").doesNotContain("toolContext");
        JsonNode hits = JSON.readTree(result);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("code").asString()).isEqualTo("FAQ-201");
        assertThat(hits.get(0).get("question").asString()).isEqualTo(seed("FAQ-201").question());
        assertThat(hits.get(0).get("answer").asString()).isEqualTo(seed("FAQ-201").answer());
        assertThat(hits.get(0).get("score").asDouble()).isCloseTo(1.0, within(0.001));
        assertThat(sources).extracting(FaqHit::code).containsExactly("FAQ-201");
    }

    @Test
    @DisplayName("임베딩 모델이 죽으면 검색은 예외 없이 빈 결과다")
    void searchFailsSoft() {
        syncService.syncAll();
        embeddingModel.failWith(new IllegalStateException("임베딩 모델 장애"));

        assertThat(searchService.search("아무 질문")).isEmpty();
    }

    private String question(String code) {
        return seed(code).question();
    }

    private FaqSeed seed(String code) {
        return seeds.stream().filter(seed -> seed.code().equals(code)).findFirst().orElseThrow();
    }

    private void deleteIndex() throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + "/" + vectorIndex.indexName()))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    private static String get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
