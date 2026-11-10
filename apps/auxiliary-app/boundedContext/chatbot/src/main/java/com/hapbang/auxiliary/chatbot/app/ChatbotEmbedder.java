package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 추천 색인용 임베딩(#718 공통 기반). 공급자 규칙(앞에 붙이는 글)과 차원 확인을 한곳에 둔다. 강의(#718)·인플루언서(#719)가 함께 쓴다.
 * <ul>
 *     <li>문서는 {@code document-prefix}, 검색어(관심사 등)는 {@code query-prefix}를 붙인다(nomic-embed-text 규칙. OpenAI는 빈 값).</li>
 *     <li>임베딩 모델이 없는 실행(모델을 끈 테스트 등)이면 {@link #available()}이 false다.</li>
 *     <li>FAQ 색인은 Spring AI vector store가 직접 임베딩한다(이 클래스를 쓰지 않는다).</li>
 * </ul>
 */
@Slf4j
@Component
public class ChatbotEmbedder {

    private final ChatbotIndexProperties properties;
    private final @Nullable EmbeddingModel model;

    ChatbotEmbedder(ChatbotIndexProperties properties, ObjectProvider<EmbeddingModel> model) {
        this.properties = properties;
        this.model = model.getIfAvailable();
        if (this.model == null) {
            log.info("임베딩 모델이 없어 추천 색인 임베딩을 끈다");
        }
    }

    public boolean available() {
        return model != null;
    }

    /**
     * 문서 글들을 임베딩한다. 공급자에 한 번에 보낸다.
     *
     * @return 글과 같은 순서
     * @throws IllegalStateException 모델이 없거나 차원이 설정과 다를 때
     */
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = model().embed(texts.stream().map(text -> properties.documentPrefix() + text).toList());
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("임베딩 결과 수가 요청과 다릅니다 expected=" + texts.size() + ", actual=" + vectors.size());
        }
        vectors.forEach(this::checkDimensions);
        return vectors;
    }

    /** 검색어를 임베딩한다 */
    public float[] embedQuery(String text) {
        return checkDimensions(model().embed(properties.queryPrefix() + text));
    }

    private float[] checkDimensions(float[] vector) {
        if (vector.length != properties.dimensions()) {
            throw new IllegalStateException("임베딩 차원이 설정과 다릅니다(hapbang.chatbot.index.dimensions) expected="
                    + properties.dimensions() + ", actual=" + vector.length);
        }
        return vector;
    }

    private EmbeddingModel model() {
        if (model == null) {
            throw new IllegalStateException("임베딩 모델이 없는 실행이다");
        }
        return model;
    }
}
