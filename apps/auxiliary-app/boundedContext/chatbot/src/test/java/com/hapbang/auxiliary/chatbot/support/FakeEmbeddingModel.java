package com.hapbang.auxiliary.chatbot.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 테스트 전용 가짜 임베딩(#715). "정해 둔 글 → 정해 둔 벡터" 표로 유사도를 통제한다. 운영 소스에 두지 않는다(docs/dev/testing.md).
 * <ul>
 *     <li>8차원. 표에 없는 문서 글은 e7, 표에 없는 질문 글({@link #QUERY_PREFIX}로 시작)은 e6이다. 둘이 직교하므로 표에 없는 질문은
 *     어떤 문서와도 유사도가 0이다. 표의 벡터는 축 0~5만 쓴다.</li>
 *     <li>글이 표의 열쇠로 시작하면 그 벡터다(FAQ 문서 글 = 질문 + 줄바꿈 + 답변이라 질문으로 시작한다). 질문 열쇠는 {@link #query}로 만든다.</li>
 *     <li>{@link #failWith}로 다음 호출부터 실패하게 만든다(임베딩 모델 장애).</li>
 * </ul>
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 8;

    /** 테스트 설정의 질문 접두어(hapbang.chatbot.faq.query-prefix). 질문 글은 이것으로 시작한다 */
    public static final String QUERY_PREFIX = "query: ";

    /** 질문의 표 열쇠 */
    public static String query(String question) {
        return QUERY_PREFIX + question;
    }

    private final Map<String, float[]> table = new ConcurrentHashMap<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicInteger embeddedTexts = new AtomicInteger();

    /** 이 글(로 시작하는 글)의 벡터를 정한다. 크기 1로 맞춘다 */
    public FakeEmbeddingModel put(String textPrefix, float... vector) {
        if (vector.length != DIMENSIONS) {
            throw new IllegalArgumentException("벡터는 " + DIMENSIONS + "차원이어야 합니다");
        }
        table.put(textPrefix, normalize(vector));
        return this;
    }

    /** 축 하나(0~5)의 단위 벡터 */
    public static float[] axis(int index) {
        float[] vector = new float[DIMENSIONS];
        vector[index] = 1;
        return vector;
    }

    /** 축 a와 코사인 유사도가 정확히 cos인 단위 벡터(나머지는 축 b 성분) */
    public static float[] withCosine(int a, int b, double cos) {
        float[] vector = new float[DIMENSIONS];
        vector[a] = (float) cos;
        vector[b] = (float) Math.sqrt(1 - cos * cos);
        return vector;
    }

    public void failWith(RuntimeException exception) {
        failure.set(exception);
    }

    public void recover() {
        failure.set(null);
    }

    /** 지금까지 임베딩한 글 수(문서·질문 모두) */
    public int embeddedTexts() {
        return embeddedTexts.get();
    }

    public void reset() {
        table.clear();
        failure.set(null);
        embeddedTexts.set(0);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        RuntimeException error = failure.get();
        if (error != null) {
            throw error;
        }
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vectorOf(texts.get(i)), i));
        }
        embeddedTexts.addAndGet(texts.size());
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput();
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private float[] vectorOf(String text) {
        return table.entrySet().stream()
                .filter(entry -> text.startsWith(entry.getKey()))
                .max((left, right) -> Integer.compare(left.getKey().length(), right.getKey().length()))
                .map(Map.Entry::getValue)
                .orElseGet(() -> text.startsWith(QUERY_PREFIX) ? unit(DIMENSIONS - 2) : unit(DIMENSIONS - 1));
    }

    private static float[] unit(int index) {
        float[] vector = new float[DIMENSIONS];
        vector[index] = 1;
        return vector;
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / Math.sqrt(norm));
        }
        return result;
    }
}
