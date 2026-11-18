package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.domain.TasteVectors;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

import lombok.RequiredArgsConstructor;

/**
 * 강의 추천 index 처리기 (#718). 요청 값이 아니라 지금 강의 사본({@code lecture_copy}) 상태로 문서를 맞춘다.
 * <ul>
 *     <li>모든 강의를 색인한다(판매 중이 아니거나 삭제돼도). 산 강의의 벡터가 취향 계산에 필요하다. 추천 후보는 kNN 필터로 거른다.</li>
 *     <li>강의 벡터는 글(제목 + 소개 + 콘텐츠 제목) 벡터에 제목 벡터를 비율 {@code title-weight}만큼 섞는다
 *     ({@link TasteVectors#mix}, #1007). 긴 소개가 제목·주제를 묻는 것을 줄인다. 비율이 0이면 제목을 임베딩하지 않고 글 벡터 그대로다.</li>
 *     <li>글과 제목 비율의 해시를 문서에 둔다. 해시가 같으면 다시 임베딩하지 않고 상태 필드만 바꾼다. 비율을 바꾸면 해시가 달라져
 *     정합 점검이 모든 강의를 다시 색인하게 한다.
 *     문서는 검색으로 읽으므로 방금 넣은 문서(refresh 전, 기본 1초)는 없다고 보고 다시 임베딩할 수 있다. 결과는 같고 비용만 든다.</li>
 *     <li>새로 임베딩할 강의는 한 번의 공급자 호출로 묶는다(제목도 같은 호출에 글 뒤로 붙인다). 묶음 호출이 실패하면 그 강의들만 실패로 돌려준다.</li>
 *     <li>사본에 없는 문서는 할 일이 없다(강의 사본은 지우지 않고 원본 없음만 표시한다).</li>
 *     <li>index가 없으면 만든다. 새로 만든 index를 모두 채우는 일은 정합 점검({@link LectureIndexReconciler})이 한다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class LectureIndexTaskHandler implements IndexTaskHandler {

    private final LectureRecommendStore store;
    private final RecommendVectorIndex index;
    private final ChatbotEmbedder embedder;
    private final ChatbotLectureRecommendProperties properties;

    @Override
    public IndexType type() {
        return IndexType.LECTURE;
    }

    @Override
    public boolean available() {
        return index.available() && embedder.available();
    }

    @Override
    public Map<String, RuntimeException> handle(SequencedMap<String, IndexAction> docs) {
        index.ensureIndex(IndexType.LECTURE, LectureIndexDocument.FIELDS);
        Map<String, RuntimeException> failures = new HashMap<>();
        Map<Long, String> docIds = new LinkedHashMap<>();
        for (String docId : docs.keySet()) {
            try {
                docIds.put(LectureIndexDocument.lectureIdOf(docId), docId);
            } catch (NumberFormatException e) {
                failures.put(docId, e);
            }
        }
        Collection<LectureSnapshot> lectures = store.lectures(docIds.keySet()).values();
        Map<String, LectureIndexDocument> stored = index.documents(IndexType.LECTURE, docIds.values(),
                LectureIndexDocument.class);

        List<LectureSnapshot> toEmbed = new ArrayList<>();
        for (LectureSnapshot lecture : lectures) {
            String docId = docIds.get(lecture.id());
            LectureIndexDocument current = LectureIndexDocument.of(lecture, properties.titleWeight());
            LectureIndexDocument previous = stored.get(docId);
            if (previous == null || !previous.sameText(current)) {
                toEmbed.add(lecture);
                continue;
            }
            try {
                // 상태 필드가 같아 보여도 보낸다. previous는 검색으로 읽어 refresh 전 쓰기를 못 보므로,
                // 짧은 사이 A→B→A로 바뀌면 같다고 보고 건너뛰어 ES에 B가 남는다
                // 문서가 그사이 사라졌으면(index 재생성 등) 새로 임베딩한다
                if (!index.update(IndexType.LECTURE, docId, current.stateFields())) {
                    toEmbed.add(lecture);
                }
            } catch (RuntimeException e) {
                failures.put(docId, e);
            }
        }
        embedAndUpsert(toEmbed, docIds, failures);
        return failures;
    }

    private void embedAndUpsert(List<LectureSnapshot> lectures, Map<Long, String> docIds, Map<String, RuntimeException> failures) {
        double titleWeight = properties.titleWeight();
        IndexEmbedBatch.embedAndUpsert(index, IndexType.LECTURE, lectures,
                lecture -> docIds.get(lecture.id()),
                batch -> vectors(batch, titleWeight),
                lecture -> LectureIndexDocument.of(lecture, titleWeight).allFields(),
                failures);
    }

    // 글 N개 뒤에 제목 N개를 붙여 한 번에 임베딩하고 강의마다 섞는다. 비율이 0이면 글만 임베딩한다(#1007 전과 같은 벡터·호출)
    private List<float[]> vectors(List<LectureSnapshot> lectures, double titleWeight) {
        List<String> texts = new ArrayList<>();
        for (LectureSnapshot lecture : lectures) {
            texts.add(lecture.indexText());
        }
        if (titleWeight == 0) {
            return embedder.embedDocuments(texts);
        }
        for (LectureSnapshot lecture : lectures) {
            texts.add(lecture.title());
        }
        List<float[]> embedded = embedder.embedDocuments(texts);
        int size = lectures.size();
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            vectors.add(TasteVectors.mix(embedded.get(i), embedded.get(size + i), titleWeight));
        }
        return vectors;
    }
}
