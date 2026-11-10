package com.hapbang.auxiliary.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hapbang.auxiliary.chatbot.app.ChatbotEmbedder;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex.Field;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex.ScoredId;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.testsupport.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 추천 벡터 index를 실제 Elasticsearch(9.5.5)로 확인한다(#718 공통 기반). 특히 ES 9의 벡터 {@code _source} 제외 아래에서
 * 저장된 벡터 읽기와 필드 일부 갱신이 되는지 고정한다(#718 "첫 작업으로 확인").
 */
@IntegrationTest
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
class RecommendVectorIndexTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final IndexType TYPE = IndexType.LECTURE;
    private static final Map<String, Field> FIELDS = Map.of("on_sale", Field.BOOLEAN, "seller_id", Field.LONG,
            "title", Field.STORED_TEXT);

    @Autowired
    private RecommendVectorIndex index;

    @Autowired
    private ChatbotEmbedder embedder;

    @Autowired
    private FakeEmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() throws Exception {
        embeddingModel.reset();
        deleteIndex();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("index 이름은 설정값이고, 없으면 만든다: 양자화 없는 hnsw·코사인·설정 차원 벡터 + 넘긴 필드만 매핑(dynamic false)")
    void ensureIndexCreatesMapping() throws Exception {
        assertThat(index.indexName(IndexType.LECTURE)).isEqualTo("chatbot-lectures-test-8");
        assertThat(index.indexName(IndexType.INFLUENCER)).isEqualTo("chatbot-influencers-test-8");

        assertThat(index.ensureIndex(TYPE, FIELDS)).isTrue();
        assertThat(index.ensureIndex(TYPE, FIELDS)).isFalse();

        JsonNode mapping = JSON.readTree(get("/" + index.indexName(TYPE) + "/_mapping"))
                .get(index.indexName(TYPE)).get("mappings");
        assertThat(mapping.get("dynamic").asString()).isEqualTo("false");
        JsonNode embedding = mapping.get("properties").get("embedding");
        assertThat(embedding.get("type").asString()).isEqualTo("dense_vector");
        assertThat(embedding.get("dims").asInt()).isEqualTo(FakeEmbeddingModel.DIMENSIONS);
        assertThat(embedding.get("similarity").asString()).isEqualTo("cosine");
        assertThat(embedding.get("index_options").get("type").asString()).isEqualTo("hnsw");
        assertThat(mapping.get("properties").get("on_sale").get("type").asString()).isEqualTo("boolean");
        assertThat(mapping.get("properties").get("seller_id").get("type").asString()).isEqualTo("long");
        assertThat(mapping.get("properties").get("title").get("index").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("kNN: 필터(on_sale=true)를 걸고 유사도 높은 순, 점수는 코사인 유사도")
    void knnWithFilter() {
        index.ensureIndex(TYPE, FIELDS);
        index.upsert(TYPE, "1", FakeEmbeddingModel.axis(0), Map.of("on_sale", true, "seller_id", 10L, "title", "같은 방향"));
        index.upsert(TYPE, "2", FakeEmbeddingModel.withCosine(0, 1, 0.6), Map.of("on_sale", true, "seller_id", 11L,
                "title", "조금 다름"));
        index.upsert(TYPE, "3", FakeEmbeddingModel.axis(0), Map.of("on_sale", false, "seller_id", 12L, "title", "판매 중 아님"));
        index.refresh(TYPE);

        List<ScoredId> hits = index.knn(TYPE, FakeEmbeddingModel.axis(0), 5, 50, Map.of("on_sale", true));

        assertThat(hits).extracting(ScoredId::id).containsExactly("1", "2");
        assertThat(hits.get(0).score()).isCloseTo(1.0, within(1e-4));
        assertThat(hits.get(1).score()).isCloseTo(0.6, within(1e-4));
    }

    @Test
    @DisplayName("ES 9는 벡터를 _source에서 빼고 저장한다. 문서를 읽으면 벡터가 없고, vectors()는 저장된 벡터를 그대로 돌려준다")
    void storedVectorsAreReadable() throws Exception {
        index.ensureIndex(TYPE, FIELDS);
        float[] vector = FakeEmbeddingModel.withCosine(0, 2, 0.8);
        index.upsert(TYPE, "7", vector, Map.of("on_sale", true, "seller_id", 10L, "title", "강의7"));
        index.refresh(TYPE);

        JsonNode source = JSON.readTree(get("/" + index.indexName(TYPE) + "/_doc/7")).get("_source");
        assertThat(source.has("embedding")).isFalse();
        assertThat(source.get("title").asString()).isEqualTo("강의7");

        Map<String, float[]> vectors = index.vectors(TYPE, List.of("7", "없음"));
        assertThat(vectors).containsOnlyKeys("7");
        assertVector(vectors.get("7"), vector);
        assertThat(index.documents(TYPE, List.of("7"), TestDocument.class))
                .containsExactly(Map.entry("7", new TestDocument("강의7", true)));
    }

    @Test
    @DisplayName("필드 일부 갱신은 벡터를 지우지 않는다. 문서가 없으면 false(새로 임베딩해 넣어야 한다)")
    void partialUpdateKeepsVector() {
        index.ensureIndex(TYPE, FIELDS);
        float[] vector = FakeEmbeddingModel.axis(1);
        index.upsert(TYPE, "8", vector, Map.of("on_sale", true, "seller_id", 10L, "title", "강의8"));

        assertThat(index.update(TYPE, "8", Map.of("on_sale", false))).isTrue();
        assertThat(index.update(TYPE, "없음", Map.of("on_sale", false))).isFalse();
        index.refresh(TYPE);

        assertVector(index.vectors(TYPE, List.of("8")).get("8"), vector);
        assertThat(index.documents(TYPE, List.of("8"), TestDocument.class).get("8").onSale()).isFalse();
        assertThat(index.knn(TYPE, vector, 5, 50, Map.of("on_sale", true))).isEmpty();
        assertThat(index.knn(TYPE, vector, 5, 50, Map.of("on_sale", false))).extracting(ScoredId::id).containsExactly("8");
    }

    @Test
    @DisplayName("지우기는 없는 문서여도 된다. 차원이 설정과 다른 벡터는 넣지 않는다")
    void deleteAndDimensionCheck() {
        index.ensureIndex(TYPE, FIELDS);
        index.upsert(TYPE, "9", FakeEmbeddingModel.axis(2), Map.of("on_sale", true, "seller_id", 10L, "title", "강의9"));

        index.delete(TYPE, "9");
        index.delete(TYPE, "없음");
        index.refresh(TYPE);

        assertThat(index.vectors(TYPE, List.of("9"))).isEmpty();
        assertThatThrownBy(() -> index.upsert(TYPE, "10", new float[3], Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("차원");
    }

    @Test
    @DisplayName("임베딩: 문서·검색어에 설정의 앞글자를 붙이고, 공급자에 한 번에 보낸다")
    void embedderUsesPrefixes() {
        embeddingModel.put("강의 소개", FakeEmbeddingModel.axis(3));
        embeddingModel.put(FakeEmbeddingModel.query("게임 방송"), FakeEmbeddingModel.axis(4));

        List<float[]> documents = embedder.embedDocuments(List.of("강의 소개", "표에 없는 글"));
        float[] query = embedder.embedQuery("게임 방송");

        assertThat(embedder.available()).isTrue();
        assertVector(documents.get(0), FakeEmbeddingModel.axis(3));
        assertThat(documents.get(1)).hasSize(FakeEmbeddingModel.DIMENSIONS);
        assertVector(query, FakeEmbeddingModel.axis(4));
    }

    private static void assertVector(float[] actual, float[] expected) {
        assertThat(actual).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(actual[i]).isCloseTo(expected[i], within(1e-5f));
        }
    }

    private void deleteIndex() throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + "/" + index.indexName(TYPE)))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    private static String get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TestDocument(String title, @JsonProperty("on_sale") boolean onSale) {
    }
}
