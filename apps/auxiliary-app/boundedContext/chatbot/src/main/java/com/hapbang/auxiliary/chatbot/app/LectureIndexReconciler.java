package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.out.IndexTaskStore;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;

/**
 * 강의 추천 index와 강의 사본을 맞추는 점검 (#718). 처음 전체 색인도 이것이 한다.
 * <ul>
 *     <li>강의 사본을 ID 순으로 나눠 읽고, index 문서가 없거나 상태·임베딩 글 해시가 사본과 다른 강의만 색인 요청({@code UPSERT})을 적는다.
 *     index가 새로 생겼거나 비었으면 모든 강의가 "문서 없음"이라 전체 색인이 된다.</li>
 *     <li>요청을 적기만 한다. 처리는 색인 실행기가 하고, 같은 문서에 쌓인 요청은 한 번에 처리한다.
 *     그래서 두 pod가 함께 점검해도 결과가 같다(요청이 겹칠 뿐이다).</li>
 *     <li>평소에는 사본을 바꾼 트랜잭션이 요청을 적으므로 할 일이 없다. 요청 유실·ES 재설치·처리 버그로 어긋난 것을 잡는 안전망이다.</li>
 * </ul>
 */
@Component
public class LectureIndexReconciler {

    private final LectureRecommendStore store;
    private final RecommendVectorIndex index;
    private final IndexTaskStore taskStore;
    private final ChatbotLectureRecommendProperties properties;
    private final Clock clock;

    LectureIndexReconciler(LectureRecommendStore store, RecommendVectorIndex index, IndexTaskStore taskStore,
            ChatbotLectureRecommendProperties properties, @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.store = store;
        this.index = index;
        this.taskStore = taskStore;
        this.properties = properties;
        this.clock = clock;
    }

    /** ES 연결이 있는 실행인지 */
    public boolean available() {
        return index.available();
    }

    /**
     * @return 색인 요청을 적은 강의 수
     */
    public int check() {
        index.ensureIndex(IndexType.LECTURE, LectureIndexDocument.FIELDS);
        int requested = 0;
        long afterId = 0;
        while (true) {
            List<LectureSnapshot> lectures = store.lecturesAfter(afterId, properties.indexCheckBatch());
            if (lectures.isEmpty()) {
                return requested;
            }
            List<String> ids = lectures.stream().map(lecture -> LectureIndexDocument.docId(lecture.id())).toList();
            Map<String, LectureIndexDocument> stored = index.documents(IndexType.LECTURE, ids, LectureIndexDocument.class);
            List<String> stale = new ArrayList<>();
            for (LectureSnapshot lecture : lectures) {
                String id = LectureIndexDocument.docId(lecture.id());
                if (!LectureIndexDocument.of(lecture).equals(stored.get(id))) {
                    stale.add(id);
                }
            }
            if (!stale.isEmpty()) {
                taskStore.addAll(IndexType.LECTURE, stale, IndexAction.UPSERT, CopyTimes.now(clock));
                requested += stale.size();
            }
            afterId = lectures.getLast().id();
        }
    }
}
