package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.CallRecorder;
import com.hapbang.auxiliary.chatbot.support.CallRecorderConfig;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/**
 * 추천 색인 처리기가 한 묶음을 넣을 때 ES를 몇 번 부르는지와, ES가 문서 하나만 거절했을 때의 문서별 실패를 함께 고정한다
 * (#835 효율 리팩터). 실패는 실행기가 {@code 예외 이름: 메시지}로 {@code index_task.last_error}에 남기므로 그 글자까지 본다.
 * <p>
 * 강의 41은 크기 0인 벡터라 ES가 문서를 거절한다(차원은 맞아 임베딩 단계는 지난다. 정규화한 값이 숫자가 아니게 된다).
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class, CallRecorderConfig.class})
class RecommendIndexCallCountTest extends ChatbotIntegrationTestBase {

    @Autowired
    private LectureIndexTaskHandler handler;

    @Autowired
    private RecommendVectorIndex index;

    @Autowired
    private FakeEmbeddingModel embedding;

    @BeforeEach
    void setUp() throws Exception {
        embedding.reset();
        deleteIndex();
        RecommendRows data = new RecommendRows(jdbcTemplate);
        data.member(100);
        lecture(data, 39, "강의 삼십구", FakeEmbeddingModel.axis(2));
        lecture(data, 40, "강의 사십", FakeEmbeddingModel.axis(0));
        lecture(data, 41, "강의 사십일", new float[8]);
        lecture(data, 42, "강의 사십이", FakeEmbeddingModel.axis(1));
        // index를 미리 만들어 둔다(처음 만드는 요청은 세지 않는다)
        assertThat(handler.handle(docs(39))).isEmpty();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("세 문서 중 하나를 ES가 거절하면 그 문서만 실패이고 나머지는 들어간다. 넣기 요청은 3회 이하")
    void oneRejected() {
        CallRecorder.Recorded<Map<String, RuntimeException>> recorded =
                CallRecorder.record(() -> handler.handle(docs(40, 41, 42)));

        Map<String, RuntimeException> failures = recorded.value();
        assertThat(failures).containsOnlyKeys("41");
        RuntimeException failure = failures.get("41");
        assertThat(failure.getClass().getSimpleName() + ": " + failure.getMessage()).isEqualTo("ElasticsearchException: [es/index] failed: [document_parsing_exception] [1:136] failed to parse: "
                + "Failed to parse object: expecting token of type [VALUE_NUMBER] but found [VALUE_STRING]");
        index.refresh(IndexType.LECTURE);
        assertThat(index.vectors(IndexType.LECTURE, List.of("40", "41", "42"))).containsOnlyKeys("40", "42");
        CallRecorder.Calls calls = recorded.calls();
        assertThat(calls.es().stream().filter(request -> !request.equals("search")).toList()).as(calls.toString())
                .hasSizeLessThanOrEqualTo(3);
    }

    private void lecture(RecommendRows data, long id, String title, float[] vector) {
        data.lecture(id, 100, title);
        embedding.put(title, vector);
    }

    private static SequencedMap<String, IndexAction> docs(long... ids) {
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        for (long id : ids) {
            docs.put(String.valueOf(id), IndexAction.UPSERT);
        }
        return docs;
    }

    private static void deleteIndex() throws Exception {
        ElasticsearchTestcontainersConfig.deleteIndex("chatbot-lectures-test-8");
    }
}
