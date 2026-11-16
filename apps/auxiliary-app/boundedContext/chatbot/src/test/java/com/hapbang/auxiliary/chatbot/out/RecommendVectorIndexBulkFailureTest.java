package com.hapbang.auxiliary.chatbot.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * bulk 요청 자체가 실패하면 묶음의 모든 문서가 실패다 (#835). 실행기는 문서마다 {@code index_task.last_error}를 남기므로
 * 문서마다 원인이 있어야 한다.
 */
class RecommendVectorIndexBulkFailureTest {

    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final RecommendVectorIndex index = new RecommendVectorIndex(properties(), provider(client));

    private final List<RecommendVectorIndex.Upsert> documents = List.of(
            new RecommendVectorIndex.Upsert("1", new float[] {1}, Map.of()),
            new RecommendVectorIndex.Upsert("2", new float[] {1}, Map.of()));

    @Test
    @DisplayName("bulk 요청이 IOException이면 모든 문서가 문서 ID를 담은 IllegalStateException으로 실패한다")
    @SuppressWarnings("unchecked")
    void ioFailure() throws IOException {
        IOException cause = new IOException("연결 끊김");
        doThrow(cause).when(client).bulk(any(Function.class));

        Map<String, RuntimeException> failures = index.upsertAll(IndexType.LECTURE, documents);

        assertThat(failures).containsOnlyKeys("1", "2");
        assertThat(failures.get("1")).isInstanceOf(IllegalStateException.class).hasCause(cause)
                .hasMessage("추천 색인 문서를 넣지 못했습니다 type=LECTURE, id=1");
        assertThat(failures.get("2")).isInstanceOf(IllegalStateException.class).hasCause(cause)
                .hasMessage("추천 색인 문서를 넣지 못했습니다 type=LECTURE, id=2");
    }

    @Test
    @DisplayName("bulk 요청이 RuntimeException이면 모든 문서가 그 예외로 실패한다")
    @SuppressWarnings("unchecked")
    void runtimeFailure() throws IOException {
        IllegalStateException cause = new IllegalStateException("ES 오류");
        doThrow(cause).when(client).bulk(any(Function.class));

        Map<String, RuntimeException> failures = index.upsertAll(IndexType.LECTURE, documents);

        assertThat(failures).containsOnlyKeys("1", "2");
        assertThat(failures.get("1")).isSameAs(cause);
        assertThat(failures.get("2")).isSameAs(cause);
    }

    private static ChatbotIndexProperties properties() {
        ChatbotIndexProperties properties = mock(ChatbotIndexProperties.class);
        doReturn("chatbot-lectures-test-1").when(properties).nameOf(IndexType.LECTURE);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ElasticsearchClient> provider(ElasticsearchClient client) {
        ObjectProvider<ElasticsearchClient> provider = mock(ObjectProvider.class);
        doReturn(client).when(provider).getIfAvailable();
        return provider;
    }
}
