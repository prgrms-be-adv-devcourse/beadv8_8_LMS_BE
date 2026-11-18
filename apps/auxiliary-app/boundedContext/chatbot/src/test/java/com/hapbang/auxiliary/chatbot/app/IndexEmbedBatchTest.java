package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

/**
 * 묶어 임베딩하고 넣기에서 한 문서의 필드를 만들다 실패하면 그 문서만 실패다 (#835). bulk로 바꾸기 전(문서마다 넣기)과 같다.
 */
class IndexEmbedBatchTest {

    private final ChatbotEmbedder embedder = mock(ChatbotEmbedder.class);
    private final RecommendVectorIndex index = mock(RecommendVectorIndex.class);

    @Test
    @DisplayName("한 문서의 필드 만들기가 던지면 그 문서만 실패로 남고 나머지는 넣는다")
    @SuppressWarnings("unchecked")
    void oneFieldBuilderThrows() {
        doReturn(List.of(new float[] {1}, new float[] {2}, new float[] {3})).when(embedder).embedDocuments(any());
        doReturn(Map.of()).when(index).upsertAll(eq(IndexType.LECTURE), any());
        IllegalStateException broken = new IllegalStateException("필드를 만들지 못함");
        Map<String, RuntimeException> failures = new LinkedHashMap<>();

        IndexEmbedBatch.embedAndUpsert(index, IndexType.LECTURE, List.of("1", "2", "3"),
                id -> id, IndexEmbedBatch.documentVectors(embedder, id -> "글" + id), id -> {
                    if (id.equals("2")) {
                        throw broken;
                    }
                    return Map.of("title", "강의" + id);
                }, failures);

        assertThat(failures).containsExactly(Map.entry("2", broken));
        ArgumentCaptor<List<RecommendVectorIndex.Upsert>> upserted = ArgumentCaptor.forClass(List.class);
        verify(index).upsertAll(eq(IndexType.LECTURE), upserted.capture());
        assertThat(upserted.getValue()).extracting(RecommendVectorIndex.Upsert::id).containsExactly("1", "3");
        assertThat(upserted.getValue().get(1).vector()).containsExactly(3f);
    }
}
