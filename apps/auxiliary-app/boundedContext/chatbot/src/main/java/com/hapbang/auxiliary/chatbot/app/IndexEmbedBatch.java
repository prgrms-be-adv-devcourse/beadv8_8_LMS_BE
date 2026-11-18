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
 *     <li>넣기는 묶음을 bulk 요청 한 번으로 하고, ES가 거절한 문서만 실패로 남긴다(문서마다 넣을 때와 같은 예외).
 *     필드를 만들다 실패한 문서는 묶음에서 빼고 실패로 남긴다.</li>
 * </ul>
 * 어떤 문서를 임베딩할지, 묶음을 벡터로 바꾸는 방법(공급자 호출 한 번), 문서의 필드는 종류마다 처리기가 정한다.
 */
final class IndexEmbedBatch {

    private IndexEmbedBatch() {
    }

    /**
     * 글만 임베딩하는 종류(인플루언서)가 쓰는 묶음 벡터: 글마다 문서 벡터 하나, 공급자 호출 한 번.
     */
    static <P> Function<List<P>, List<float[]>> documentVectors(ChatbotEmbedder embedder, Function<P, String> text) {
        return pending -> {
            List<String> texts = new ArrayList<>();
            for (P one : pending) {
                texts.add(text.apply(one));
            }
            return embedder.embedDocuments(texts);
        };
    }

    /**
     * @param pending  임베딩해 넣을 문서(비어 있으면 아무것도 하지 않는다)
     * @param docId    문서 ID
     * @param vectors  묶음의 벡터를 같은 순서로 만든다. 공급자 호출은 한 번이다. 실패하면 묶음의 모든 문서가 실패다
     * @param fields   벡터와 함께 넣을 필드
     * @param failures 실패한 문서 ID → 원인을 더한다
     */
    static <P> void embedAndUpsert(RecommendVectorIndex index, IndexType type, List<P> pending,
            Function<P, String> docId, Function<List<P>, List<float[]>> vectors, Function<P, Map<String, Object>> fields,
            Map<String, RuntimeException> failures) {
        if (pending.isEmpty()) {
            return;
        }
        List<float[]> embedded;
        try {
            embedded = vectors.apply(pending);
        } catch (RuntimeException e) {
            for (P one : pending) {
                failures.put(docId.apply(one), e);
            }
            return;
        }
        List<RecommendVectorIndex.Upsert> documents = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            P one = pending.get(i);
            String id = docId.apply(one);
            // 필드를 만들다 실패한 문서만 실패로 남기고 묶음에서 뺀다
            try {
                documents.add(new RecommendVectorIndex.Upsert(id, embedded.get(i), fields.apply(one)));
            } catch (RuntimeException e) {
                failures.put(id, e);
            }
        }
        failures.putAll(index.upsertAll(type, documents));
    }
}
