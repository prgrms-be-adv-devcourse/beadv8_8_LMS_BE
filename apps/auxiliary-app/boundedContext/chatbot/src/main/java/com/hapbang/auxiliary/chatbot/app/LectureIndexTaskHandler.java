package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

import lombok.RequiredArgsConstructor;

/**
 * 강의 추천 index 처리기 (#718). 요청 값이 아니라 지금 강의 사본({@code lecture_copy}) 상태로 문서를 맞춘다.
 * <ul>
 *     <li>모든 강의를 색인한다(판매 중이 아니거나 삭제돼도). 산 강의의 벡터가 취향 계산에 필요하다. 추천 후보는 kNN 필터로 거른다.</li>
 *     <li>임베딩하는 글(제목 + 소개 + 콘텐츠 제목)의 해시를 문서에 둔다. 해시가 같으면 다시 임베딩하지 않고 상태 필드만 바꾼다.
 *     문서는 검색으로 읽으므로 방금 넣은 문서(refresh 전, 기본 1초)는 없다고 보고 다시 임베딩할 수 있다. 결과는 같고 비용만 든다.</li>
 *     <li>새로 임베딩할 강의는 한 번의 공급자 호출로 묶는다. 묶음 호출이 실패하면 그 강의들만 실패로 돌려준다.</li>
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
                docIds.put(Long.parseLong(docId), docId);
            } catch (NumberFormatException e) {
                failures.put(docId, e);
            }
        }
        List<LectureSnapshot> lectures = store.lectures(docIds.keySet());
        Map<String, LectureIndexDocument> stored = index.documents(IndexType.LECTURE, docIds.values(),
                LectureIndexDocument.class);

        List<LectureSnapshot> toEmbed = new ArrayList<>();
        for (LectureSnapshot lecture : lectures) {
            String docId = docIds.get(lecture.id());
            LectureIndexDocument current = LectureIndexDocument.of(lecture);
            LectureIndexDocument previous = stored.get(docId);
            if (previous == null || !previous.sameText(current)) {
                toEmbed.add(lecture);
                continue;
            }
            try {
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
        if (lectures.isEmpty()) {
            return;
        }
        List<float[]> vectors;
        try {
            vectors = embedder.embedDocuments(lectures.stream().map(LectureSnapshot::indexText).toList());
        } catch (RuntimeException e) {
            for (LectureSnapshot lecture : lectures) {
                failures.put(docIds.get(lecture.id()), e);
            }
            return;
        }
        for (int i = 0; i < lectures.size(); i++) {
            LectureSnapshot lecture = lectures.get(i);
            String docId = docIds.get(lecture.id());
            try {
                index.upsert(IndexType.LECTURE, docId, vectors.get(i), LectureIndexDocument.of(lecture).allFields());
            } catch (RuntimeException e) {
                failures.put(docId, e);
            }
        }
    }
}
