package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

/**
 * 추천 index 처리기(강의·인플루언서)가 함께 쓰는 "묶어 임베딩하고 문서마다 넣기" (#718, #719).
 * <ul>
 *     <li>새로 임베딩할 문서의 글은 한 번의 공급자 호출로 묶는다. 묶음 호출이 실패하면 그 문서들만 실패로 남긴다.</li>
 *     <li>넣기는 묶음을 bulk 요청 한 번으로 하고, ES가 거절한 문서만 실패로 남긴다(문서마다 넣을 때와 같은 예외).</li>
 * </ul>
 * 어떤 문서를 임베딩할지, 문서의 글·필드는 종류마다 처리기가 정한다.
 */
final class IndexEmbedBatch {

    private IndexEmbedBatch() {
    }

    /**
     * @param pending  임베딩해 넣을 문서(비어 있으면 아무것도 하지 않는다)
     * @param docId    문서 ID
     * @param text     임베딩할 글
     * @param fields   벡터와 함께 넣을 필드
     * @param failures 실패한 문서 ID → 원인을 더한다
     */
    static <P> void embedAndUpsert(ChatbotEmbedder embedder, RecommendVectorIndex index, IndexType type, List<P> pending,
            Function<P, String> docId, Function<P, String> text, Function<P, Map<String, Object>> fields,
            Map<String, RuntimeException> failures) {
        if (pending.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<>();
        for (P one : pending) {
            texts.add(text.apply(one));
        }
        List<float[]> vectors;
        try {
            vectors = embedder.embedDocuments(texts);
        } catch (RuntimeException e) {
            for (P one : pending) {
                failures.put(docId.apply(one), e);
            }
            return;
        }
        List<RecommendVectorIndex.Upsert> documents = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            P one = pending.get(i);
            documents.add(new RecommendVectorIndex.Upsert(docId.apply(one), vectors.get(i), fields.apply(one)));
        }
        failures.putAll(index.upsertAll(type, documents));
    }
}
