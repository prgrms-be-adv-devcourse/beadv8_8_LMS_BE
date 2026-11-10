package com.hapbang.auxiliary.chatbot.out;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptionsType;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.SourceFilter;
import lombok.extern.slf4j.Slf4j;

/**
 * 추천 벡터 index(Elasticsearch, #718 공통 기반). 강의(#718)·인플루언서(#719) index가 함께 쓴다.
 * <p>
 * FAQ와 달리 Spring AI {@code VectorStore}를 쓰지 않고 {@link ElasticsearchClient}로 직접 다룬다. {@code VectorStore}는 문자열만 받아
 * 스스로 임베딩하므로 취향 벡터(가중 평균) 검색, 저장된 벡터 읽기, 필드 일부 갱신이 안 된다. 임베딩은 부르는 쪽(app)이 한다.
 * <ul>
 *     <li>client는 Boot 자동 설정 빈이다({@code spring.elasticsearch.*}, 챗봇 key). ES 연결이 없는 실행이면 {@link #available()}이 false다.
 *     생성자에서 ES를 부르지 않는다(ES가 죽어 있어도 앱은 뜬다).</li>
 *     <li>매핑: {@value #EMBEDDING_FIELD}({@code dense_vector}, 코사인, 양자화하지 않는 {@code hnsw})와 기능이 넘긴 필드. 그 밖의 필드는
 *     매핑하지 않는다({@code dynamic: false}). 수천 건이라 양자화 없이도 메모리 부담이 없고, 점수가 근사값이 되지 않는다.</li>
 *     <li><b>ES 9의 벡터 {@code _source} 제외</b>: 새 index는 {@code dense_vector}를 {@code _source}에서 빼고 저장한다
 *     ({@code index.mapping.exclude_source_vectors} 기본 true). 문서를 읽으면 벡터가 없고, kNN 응답도 가볍다.
 *     저장된 벡터가 필요하면 {@link #vectors}가 {@code _source.exclude_vectors: false}로 검색해 다시 붙여 받는다.
 *     필드 일부 갱신({@link #update})은 벡터를 지우지 않는다. 둘 다 {@code RecommendVectorIndexTest}가 고정한다.</li>
 *     <li>쓰기는 refresh를 기다리지 않는다(실행기가 수천 건을 넣는다). 바로 검색해야 하는 쪽(테스트)은 {@link #refresh}를 부른다.</li>
 * </ul>
 */
@Slf4j
@Component
public class RecommendVectorIndex {

    static final String EMBEDDING_FIELD = "embedding";

    private final ChatbotIndexProperties properties;
    private final @Nullable ElasticsearchClient client;

    RecommendVectorIndex(ChatbotIndexProperties properties, ObjectProvider<ElasticsearchClient> client) {
        this.properties = properties;
        this.client = client.getIfAvailable();
        if (this.client == null) {
            log.info("Elasticsearch 연결이 없어 추천 색인·검색을 끈다");
        }
    }

    /** 색인·검색을 할 수 있는 실행인지(ES 연결이 있다) */
    public boolean available() {
        return client != null;
    }

    /** 종류의 ES index 이름 */
    public String indexName(IndexType type) {
        return properties.nameOf(type);
    }

    /**
     * index가 없으면 만든다.
     *
     * @param fields 벡터 밖의 필드 매핑(필터·정렬·표시용)
     * @return 새로 만들었으면 true. 모두 다시 색인해야 한다
     */
    public boolean ensureIndex(IndexType type, Map<String, Field> fields) {
        String index = indexName(type);
        try {
            if (es().indices().exists(e -> e.index(index)).value()) {
                return false;
            }
            Map<String, Property> mappings = new LinkedHashMap<>();
            mappings.put(EMBEDDING_FIELD, Property.of(p -> p.denseVector(dv -> dv
                    .dims(properties.dimensions())
                    .similarity(DenseVectorSimilarity.Cosine)
                    .index(true)
                    .indexOptions(io -> io.type(DenseVectorIndexOptionsType.Hnsw)))));
            fields.forEach((name, field) -> mappings.put(name, field.property()));
            es().indices().create(c -> c.index(index)
                    .mappings(m -> m.dynamic(DynamicMapping.False)
                            .properties(mappings)));
            log.info("추천 색인 index를 만들었다 type={}, index={}", type, index);
            return true;
        } catch (ElasticsearchException e) {
            // 두 pod가 동시에 만들면 한쪽은 이미 있다는 오류를 받는다. 이미 있으면 만든 것과 같다
            if ("resource_already_exists_exception".equals(e.error().type())) {
                return false;
            }
            throw new IllegalStateException("추천 색인 index를 만들지 못했습니다 index=" + index, e);
        } catch (IOException e) {
            throw new IllegalStateException("추천 색인 index를 확인하지 못했습니다 index=" + index, e);
        }
    }

    /**
     * 문서를 벡터와 함께 넣는다(같은 ID면 통째로 바꾼다).
     *
     * @param vector 임베딩({@code ChatbotEmbedder}가 차원을 확인한 값). 차원이 매핑과 다르면 ES가 거절한다
     * @param fields 벡터 밖의 필드 값
     */
    public void upsert(IndexType type, String id, float[] vector, Map<String, Object> fields) {
        Map<String, Object> document = new HashMap<>(fields);
        document.put(EMBEDDING_FIELD, vector);
        try {
            es().index(i -> i.index(indexName(type)).id(id).document(document));
        } catch (IOException e) {
            throw new IllegalStateException("추천 색인 문서를 넣지 못했습니다 type=" + type + ", id=" + id, e);
        }
    }

    /**
     * 문서의 필드 일부만 바꾼다. 벡터는 그대로다.
     *
     * @return 문서가 없어 바꾸지 못했으면 false(새로 임베딩해 넣어야 한다)
     */
    public boolean update(IndexType type, String id, Map<String, Object> fields) {
        return unlessNotFound("갱신", type, id, () -> es().update(u -> u.index(indexName(type)).id(id).doc(fields), Map.class));
    }

    /** 문서를 지운다. 없어도 된다 */
    public void delete(IndexType type, String id) {
        unlessNotFound("삭제", type, id, () -> es().delete(d -> d.index(indexName(type)).id(id)));
    }

    /**
     * 문서의 벡터 밖 필드를 읽는다(벡터는 빠진다).
     *
     * @return ID → 문서. 없는 ID는 빠진다
     */
    public <T> Map<String, T> documents(IndexType type, Collection<String> ids, Class<T> documentClass) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, T> documents = new LinkedHashMap<>();
        for (Hit<T> hit : searchByIds(type, ids, SourceFilter.of(f -> f.excludes(EMBEDDING_FIELD)), documentClass)) {
            if (hit.id() != null && hit.source() != null) {
                documents.put(hit.id(), hit.source());
            }
        }
        return documents;
    }

    /**
     * 저장된 벡터를 읽는다(취향 벡터 계산용).
     *
     * @return ID → 벡터. 없는 ID는 빠진다
     */
    public Map<String, float[]> vectors(IndexType type, Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, float[]> vectors = new LinkedHashMap<>();
        SourceFilter withVectors = SourceFilter.of(f -> f.includes(EMBEDDING_FIELD).excludeVectors(false));
        for (Hit<VectorSource> hit : searchByIds(type, ids, withVectors, VectorSource.class)) {
            if (hit.id() != null && hit.source() != null && hit.source().embedding() != null) {
                vectors.put(hit.id(), toArray(hit.source().embedding()));
            }
        }
        return vectors;
    }

    /**
     * 벡터와 가까운 문서를 찾는다(코사인).
     *
     * @param k             돌려줄 수
     * @param numCandidates 샤드마다 살펴볼 후보 수(k 이상)
     * @param filters       모두 맞아야 하는 필드 값(예: {@code on_sale = true})
     * @return 유사도 높은 순. score는 코사인 유사도
     */
    public List<ScoredId> knn(IndexType type, float[] vector, int k, int numCandidates, Map<String, Object> filters) {
        List<Float> query = new ArrayList<>(vector.length);
        for (float value : vector) {
            query.add(value);
        }
        List<Query> terms = filters.entrySet().stream()
                .map(filter -> Query.of(q -> q.term(t -> t.field(filter.getKey()).value(FieldValue.of(filter.getValue())))))
                .toList();
        try {
            return es().search(s -> s.index(indexName(type))
                                    .knn(kn -> kn.field(EMBEDDING_FIELD).queryVector(query).k(k).numCandidates(numCandidates)
                                            .filter(terms))
                                    .size(k)
                                    .source(src -> src.fetch(false)),
                            Void.class)
                    .hits().hits().stream()
                    .filter(hit -> hit.id() != null)
                    // ES 코사인 kNN의 _score는 (1 + 코사인) / 2다
                    .map(hit -> new ScoredId(hit.id(), 2 * Objects.requireNonNullElse(hit.score(), 0.5) - 1))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("추천 색인을 검색하지 못했습니다 type=" + type, e);
        }
    }

    /** 넣은 문서가 바로 검색되게 한다(테스트·전체 색인 뒤) */
    public void refresh(IndexType type) {
        try {
            es().indices().refresh(r -> r.index(indexName(type)));
        } catch (IOException e) {
            throw new IllegalStateException("추천 색인을 refresh하지 못했습니다 type=" + type, e);
        }
    }

    // ID로 문서를 읽는다. 스펙의 mget 대신 ids 검색을 쓴다: mget에는 _source.exclude_vectors가 없어 ES 9에서 벡터를 받을 수 없다
    private <T> List<Hit<T>> searchByIds(IndexType type, Collection<String> ids, SourceFilter source, Class<T> documentClass) {
        try {
            return es().search(s -> s.index(indexName(type))
                    .query(q -> q.ids(i -> i.values(List.copyOf(ids))))
                    .size(ids.size())
                    .source(src -> src.filter(source)), documentClass).hits().hits();
        } catch (IOException e) {
            throw new IllegalStateException("추천 색인 문서를 읽지 못했습니다 type=" + type, e);
        }
    }

    // 문서 하나를 바꾼다. 문서가 없으면(404) false
    private boolean unlessNotFound(String what, IndexType type, String id, EsCall call) {
        try {
            call.run();
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return false;
            }
            throw new IllegalStateException("추천 색인 문서 " + what + "에 실패했습니다 type=" + type + ", id=" + id, e);
        } catch (IOException e) {
            throw new IllegalStateException("추천 색인 문서 " + what + "에 실패했습니다 type=" + type + ", id=" + id, e);
        }
    }

    private ElasticsearchClient es() {
        if (client == null) {
            throw new IllegalStateException("추천 색인을 쓸 수 없는 실행입니다(ES 연결 없음)");
        }
        return client;
    }

    @FunctionalInterface
    private interface EsCall {
        void run() throws IOException;
    }

    private static float[] toArray(List<Float> values) {
        float[] array = new float[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /** 벡터 밖 필드의 매핑 종류 */
    public enum Field {
        /** 정확히 맞추는 문자열(필터·집계) */
        KEYWORD,
        /** 정수 */
        LONG,
        /** 참·거짓(필터) */
        BOOLEAN,
        /** 저장만 하고 검색하지 않는 문자열(표시·해시) */
        STORED_TEXT;

        Property property() {
            return switch (this) {
                case KEYWORD -> Property.of(p -> p.keyword(k -> k));
                case LONG -> Property.of(p -> p.long_(l -> l));
                case BOOLEAN -> Property.of(p -> p.boolean_(b -> b));
                case STORED_TEXT -> Property.of(p -> p.keyword(k -> k.index(false).docValues(false)));
            };
        }
    }

    /** 검색 결과 하나. score는 코사인 유사도 */
    public record ScoredId(String id, double score) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VectorSource(@Nullable List<Float> embedding) {
    }
}
