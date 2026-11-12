package com.hapbang.auxiliary.chatbot.llm.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.auxiliary.chatbot.app.RecommendEvalAccess;
import com.hapbang.auxiliary.chatbot.domain.InfluencerProfileText;
import com.hapbang.auxiliary.chatbot.domain.LectureSignals;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Answer;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Influencer;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Lecture;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Member;
import com.hapbang.auxiliary.chatbot.out.InfluencerRecommendStore;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 추천 평가가 끝까지 도는지 가짜 임베딩으로 확인한다(#867). 숫자의 좋고 나쁨은 보지 않는다(가짜 벡터라 의미가 없다).
 * 실제 공급자를 부르지 않지만 PostgreSQL·ES를 띄우고 회원 100명 × 조합을 돌아 오래 걸리므로 평가와 같이 {@code @Tag("llm")}이다.
 * <p>
 * 가짜 벡터: 글마다 분야 방향(8분야를 8차원 중 축 0~5에 나눠 둔다) + 항목마다 조금 다른 방향. 관심사도 분야 방향이다.
 */
@Tag("llm")
@IntegrationTest
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
@TestPropertySource(properties = {
        "hapbang.chatbot.index.lecture-name=chatbot-lectures-eval-8",
        "hapbang.chatbot.index.influencer-name=chatbot-influencers-eval-8"
})
class RecommendEvaluationFakeTest extends RecommendEvaluation {

    @TempDir
    static Path cacheDir;

    @Autowired
    private FakeEmbeddingModel fakeModel;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LectureRecommendStore lectureStore;

    @Autowired
    private InfluencerRecommendStore influencerStore;

    @Override
    String modelName() {
        return "fake-8";
    }

    @Override
    Path cacheDir() {
        return cacheDir;
    }

    @Override
    void beforeLoad(RecommendEvalData data) {
        fakeModel.reset();
        Map<String, String> names = data.fieldNames();
        List<String> codes = data.fields().stream().map(RecommendEvalData.Field::code).toList();
        for (Lecture lecture : data.lectures()) {
            LectureSnapshot snapshot = new LectureSnapshot(lecture.id(), lecture.sellerId(), lecture.title(),
                    lecture.description(), lecture.contentTitles(), true, false, false);
            fakeModel.put(snapshot.indexText(), vector(codes.indexOf(lecture.field()), lecture.id()));
        }
        for (Influencer influencer : data.influencers()) {
            List<String> categoryNames = influencer.fields().stream().map(names::get).toList();
            fakeModel.put(InfluencerProfileText.of(categoryNames, influencer.bio()).text(),
                    vector(codes.indexOf(influencer.fields().getFirst()), influencer.id()));
        }
        for (Member member : data.members()) {
            fakeModel.put(FakeEmbeddingModel.query(member.interest()), vector(codes.indexOf(member.tasteFields().getFirst()), 0));
        }
    }

    // 분야 f의 방향: 0~5는 축 하나, 6·7은 두 축 사이. 항목 ID로 조금 흔든다(동점만 나오지 않게)
    private static float[] vector(int field, long id) {
        float[] vector = new float[FakeEmbeddingModel.DIMENSIONS];
        if (field < 6) {
            vector[field] = 1;
        } else {
            vector[(field - 6) * 2] = 0.7f;
            vector[(field - 6) * 2 + 1] = 0.7f;
        }
        vector[(int) (id % 6)] += 0.15f;
        return vector;
    }

    @Test
    void answersAreHiddenFromSignalsAndExclusions() {
        for (Answer answer : data.lectureAnswers()) {
            long memberId = answer.requesterId();
            for (LectureSignals.Activity activity : lectureStore.activities(memberId)) {
                assertThat(activity.lectureId()).as("회원 %d의 신호", memberId).isNotEqualTo(answer.answerId());
            }
            assertThat(lectureStore.ratings(memberId)).as("회원 %d의 별점", memberId).doesNotContainKey(answer.answerId());
            assertThat(lectureStore.excludedLectures(memberId)).as("회원 %d의 제외 목록", memberId)
                    .doesNotContain(answer.answerId());
        }
        for (Answer answer : data.influencerAnswers()) {
            assertThat(influencerStore.completedPartners(answer.requesterId())).as("인플루언서 %d의 상대", answer.requesterId())
                    .doesNotContain(answer.answerId())
                    .isNotEmpty();
        }
    }

    @Test
    void secondLoadDoesNotCallModel() {
        assertThat(cache.delegateTexts()).as("첫 실행은 모델을 부른다").isPositive();
        int embedded = fakeModel.embeddedTexts();

        CachingEmbeddingModel again = new CachingEmbeddingModel(fakeModel, modelName(), FakeEmbeddingModel.DIMENSIONS, cacheDir);
        new RecommendEvalLoader(new RecommendRows(jdbc), new RecommendEvalAccess(context, again)).vectors(data);

        assertThat(again.delegateTexts()).isZero();
        assertThat(fakeModel.embeddedTexts()).isEqualTo(embedded);
    }

    @Test
    void sameInputGivesSameNumbers() {
        List<RecommendEvalGrid.Combo> combos =
                RecommendEvalGrid.combos("lecture.popularity-weight=0,0.3;influencer.partner-weight=0.5", null);

        List<Row> first = evaluateAndPrint(Group.TUNING, combos, null);
        List<Row> second = evaluate(Group.TUNING, combos);

        assertThat(second).isEqualTo(first);
        // 2 × 1 조합 × (강의 2줄 + 인플루언서 1줄), 회원 80명
        assertThat(first).hasSize(6).allSatisfy(row -> assertThat(row.members()).isEqualTo(80));
        assertThat(first.getFirst().hitAt5()).as("분야 벡터면 숨긴 구매를 일부는 맞힌다").isPositive();
    }

    @Test
    void holdoutGroupHasTwenty() {
        List<Row> rows = evaluateAndPrint(Group.HOLDOUT, RecommendEvalGrid.combos(null, "lecture.popularity-weight=0.1"),
                "lecture.popularity-weight=0.1");

        assertThat(rows).extracting(Row::members).containsOnly(20);
    }
}
