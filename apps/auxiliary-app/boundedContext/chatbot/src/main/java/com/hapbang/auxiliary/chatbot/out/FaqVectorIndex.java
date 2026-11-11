package com.hapbang.auxiliary.chatbot.out;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.ContentFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.config.ChatbotFaqProperties;
import com.hapbang.auxiliary.chatbot.domain.Faq;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptionsType;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * FAQ 벡터 색인(Elasticsearch, #715). Spring AI {@link ElasticsearchVectorStore}로 임베딩·저장·kNN 검색을 하고, index는 직접 만든다.
 * <ul>
 *     <li>Spring AI 자동 설정의 vector store 빈은 쓰지 않는다({@code spring.ai.vectorstore.type=none}). 그 빈은 만들어질 때 ES에 index가
 *     있는지 물어서, ES가 죽어 있으면 앱이 뜨지 않는다. 여기서는 생성자에서 ES를 부르지 않고, index는 색인 작업이 만든다.</li>
 *     <li>index mapping을 직접 만든다. ES 9의 {@code dense_vector}는 차원이 크면 기본으로 양자화(bbq/int8)해서 점수가 근사값이 된다.
 *     FAQ는 수십 건이라 메모리 부담이 없으므로 양자화하지 않는 {@code hnsw}로 두어 유사도 기준값이 정확히 맞게 한다.</li>
 *     <li>문서 id는 FAQ code, metadata는 {@code code}·{@code category}다. 임베딩에는 본문만 넣는다(OpenAI 임베딩은 기본으로 metadata까지
 *     넣는데, 공급자마다 달라지지 않게 formatter로 본문만 쓴다).</li>
 *     <li>검색 기준값은 코사인 유사도다. ES kNN의 {@code similarity}는 코사인 값 그대로 비교하고, Spring AI가 돌려주는 점수도
 *     {@code 2 × _score − 1} = 코사인 값이다. 이 관계는 {@code FaqIndexingIntegrationTest.similarityThresholdBoundary}가 고정한다.</li>
 *     <li>임베딩 모델이나 ES 연결이 없는 실행(모델을 끈 테스트 등)에서는 {@link #available()}이 false이고 아무것도 하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class FaqVectorIndex {

    static final String EMBEDDING_FIELD = "embedding";

    /** 임베딩·저장 글에 본문만 쓴다 */
    private static final ContentFormatter TEXT_ONLY = (document, mode) -> Objects.requireNonNullElse(document.getText(), "");

    private final ChatbotFaqProperties properties;
    private final @Nullable ElasticsearchVectorStore store;
    private final @Nullable ElasticsearchClient client;

    FaqVectorIndex(ChatbotFaqProperties properties, ObjectProvider<Rest5Client> restClient,
            ObjectProvider<EmbeddingModel> embeddingModel, ObjectProvider<ObservationRegistry> observationRegistry) {
        this.properties = properties;
        Rest5Client rest = restClient.getIfAvailable();
        EmbeddingModel embedding = embeddingModel.getIfAvailable();
        if (rest == null || embedding == null) {
            log.info("임베딩 모델이나 Elasticsearch 연결이 없어 FAQ 색인·검색을 끈다 embeddingModel={}, elasticsearch={}",
                    embedding != null, rest != null);
            this.store = null;
            this.client = null;
            return;
        }
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(properties.indexName());
        options.setDimensions(properties.dimensions());
        options.setSimilarity(SimilarityFunction.cosine);
        options.setEmbeddingFieldName(EMBEDDING_FIELD);
        this.store = ElasticsearchVectorStore.builder(rest, embedding)
                .options(options)
                .initializeSchema(false)
                .batchingStrategy(new TokenCountBatchingStrategy())
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .build();
        this.client = store.<ElasticsearchClient>getNativeClient().orElseThrow();
    }

    /** 색인·검색을 할 수 있는 실행인지(임베딩 모델과 ES 연결이 있다) */
    public boolean available() {
        return store != null;
    }

    public String indexName() {
        return properties.indexName();
    }

    /**
     * index가 없으면 만든다.
     *
     * @return index를 새로 만들었거나 문서가 하나도 없으면 true. 모두 다시 색인해야 한다
     */
    public boolean ensureIndex() {
        ElasticsearchClient es = requireClient();
        try {
            if (!es.indices().exists(e -> e.index(properties.indexName())).value()) {
                es.indices().create(c -> c.index(properties.indexName())
                        .mappings(m -> m
                                .properties(EMBEDDING_FIELD, p -> p.denseVector(dv -> dv
                                        .dims(properties.dimensions())
                                        .similarity(DenseVectorSimilarity.Cosine)
                                        .index(true)
                                        .indexOptions(io -> io.type(DenseVectorIndexOptionsType.Hnsw))))
                                .properties("content", p -> p.text(t -> t.index(false)))
                                .properties("metadata", p -> p.object(o -> o
                                        .properties("code", k -> k.keyword(kw -> kw))
                                        .properties("category", k -> k.keyword(kw -> kw))))));
                log.info("FAQ 색인 index를 만들었다 index={}", properties.indexName());
                return true;
            }
            return count() == 0;
        } catch (IOException e) {
            throw new IllegalStateException("FAQ 색인 index를 확인하지 못했습니다 index=" + properties.indexName(), e);
        }
    }

    /** FAQ를 임베딩해 넣는다(같은 code면 덮어쓴다). 하나라도 실패하면 예외다 */
    public void upsert(List<Faq> faqs) {
        if (faqs.isEmpty()) {
            return;
        }
        List<Document> documents = faqs.stream().map(this::toDocument).toList();
        requireStore().add(documents);
        refresh();
    }

    /** code의 문서를 지운다 */
    public void delete(Collection<String> codes) {
        if (codes.isEmpty()) {
            return;
        }
        requireStore().delete(List.copyOf(codes));
        refresh();
    }

    /** 색인에 있는 모든 code. 시드에서 빠진 FAQ를 찾아 지울 때 쓴다(FAQ는 수십 건이라 한 번에 읽는다) */
    public Set<String> codes() {
        try {
            return requireClient().search(s -> s.index(properties.indexName()).size(10_000).source(src -> src.fetch(false)),
                            Void.class)
                    .hits().hits().stream()
                    .map(Hit::id)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new IllegalStateException("FAQ 색인의 문서 목록을 읽지 못했습니다", e);
        }
    }

    public long count() {
        try {
            return requireClient().count(c -> c.index(properties.indexName())).count();
        } catch (IOException e) {
            throw new IllegalStateException("FAQ 색인 문서 수를 읽지 못했습니다", e);
        }
    }

    /**
     * 질문과 가까운 FAQ를 찾는다. 기준값 아래는 버린다.
     *
     * @return 유사도 높은 순. 색인을 쓸 수 없는 실행이면 빈 목록
     * @throws RuntimeException ES·임베딩 모델 호출이 실패했을 때
     */
    public List<ScoredCode> search(String question) {
        if (store == null) {
            return List.of();
        }
        List<Document> documents = store.similaritySearch(SearchRequest.builder()
                .query(properties.queryPrefix() + question)
                .topK(properties.topK())
                .similarityThreshold(properties.similarityThreshold())
                .build());
        return documents.stream()
                .map(document -> new ScoredCode(document.getId(), document.getScore() == null ? 0 : document.getScore()))
                .toList();
    }

    private Document toDocument(Faq faq) {
        Document document = Document.builder()
                .id(faq.getCode())
                .text(properties.documentPrefix() + faq.embeddingText())
                .metadata(Map.of("code", faq.getCode(), "category", faq.getCategory().name()))
                .build();
        document.setContentFormatter(TEXT_ONLY);
        return document;
    }

    // 넣은 문서가 바로 검색되게 한다. FAQ 색인은 드물게 돌아 refresh 비용이 문제되지 않는다
    private void refresh() {
        try {
            requireClient().indices().refresh(r -> r.index(properties.indexName()));
        } catch (IOException e) {
            throw new IllegalStateException("FAQ 색인을 refresh하지 못했습니다", e);
        }
    }

    private ElasticsearchVectorStore requireStore() {
        return require(store);
    }

    private ElasticsearchClient requireClient() {
        return require(client);
    }

    private static <T> T require(@Nullable T component) {
        if (component == null) {
            throw new IllegalStateException("FAQ 색인을 쓸 수 없는 실행이다(임베딩 모델·ES 연결 없음)");
        }
        return component;
    }

    /** 검색 결과 하나. score는 코사인 유사도 */
    public record ScoredCode(String code, double score) {
    }
}
