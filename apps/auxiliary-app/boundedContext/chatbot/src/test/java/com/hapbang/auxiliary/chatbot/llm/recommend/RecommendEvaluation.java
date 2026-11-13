package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.auxiliary.chatbot.app.RecommendEvalAccess;
import com.hapbang.auxiliary.chatbot.config.ChatbotIndexProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Member;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Split;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalGrid.Combo;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendMetrics.Pick;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendMetrics.Tally;
import com.hapbang.auxiliary.chatbot.support.CopyRows;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/**
 * 추천 가중치 평가(#867). 더미 데이터를 한 번 넣고, 가중치 조합마다 회원별로 <b>운영 추천 UseCase를 그대로</b> 불러
 * hit@5·nDCG@5·풀 recall@20·상위 5개의 서로 다른 항목 수를 표로 찍는다({@link RecommendMetrics}).
 * <ul>
 *     <li>회원마다 결과 수 5와 20으로 두 번 부른다. hit@5·nDCG@5는 사용자가 보는 5개, recall@20은 20개 중 맞춤 후보만 센다.</li>
 *     <li>강의는 "신호만"과 "신호+관심사" 두 줄이다. α(관심사 비율)는 신호와 관심사가 함께 있을 때만 쓰여 두 번째 줄에서만 숫자가 바뀐다.
 *     인플루언서는 관심사를 주면 취향이 관심사뿐이라(상대 가중을 쓰지 않음) 관심사 없이 잰다.</li>
 *     <li>정답은 {@link RecommendEvalHoldout}로 숨긴 뒤 넣는다. 조정용 80명과 확인용 20명은 데이터 파일에 고정돼 있다.</li>
 * </ul>
 * 하위 클래스가 임베딩 공급자를 정한다. 실제 공급자를 부르므로 {@code @Tag("llm")}이고 CI에서 돌지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RecommendEvaluation {

    /** 확인용 20명을 켜는 환경 변수(값 {@code yes}) */
    static final String HOLDOUT_ENV = "RECOMMEND_EVAL_HOLDOUT";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddingModel embeddingModel;

    protected RecommendEvalData data;
    protected RecommendEvalAccess access;
    protected CachingEmbeddingModel cache;
    protected RecommendEvalGrid grid;

    /** 캐시 파일 이름과 표에 찍을 모델 이름. 공급자·모델·차원이 다르면 다르게 */
    abstract String modelName();

    abstract Path cacheDir();

    /** 데이터를 넣기 전에 할 일(가짜 모델의 벡터 표 등) */
    void beforeLoad(RecommendEvalData data) {
    }

    /** 평가 대상 회원 묶음 */
    enum Group {

        TUNING("조정용 80명"),
        HOLDOUT("확인용 20명");

        private final String description;

        Group(String description) {
            this.description = description;
        }

        String description() {
            return description;
        }

        List<Long> of(Split split) {
            return this == TUNING ? split.tuning() : split.holdout();
        }
    }

    /** 강의 평가 입력 */
    enum LectureInput {

        SIGNAL("신호만"),
        SIGNAL_AND_INTEREST("신호+관심사");

        private final String description;

        LectureInput(String description) {
            this.description = description;
        }

        String description() {
            return description;
        }
    }

    /**
     * 표 한 줄.
     *
     * @param change 바꾼 값(전 → 후). 격자 없이 돌렸으면 "기준선" 또는 "고정값"
     */
    record Row(
            String target,
            String change,
            String input,
            int members,
            double hitAt5,
            double ndcgAt5,
            double recallAt20,
            int distinctTop5
    ) {

        static Row of(String target, String change, String input, Tally tally) {
            return new Row(target, change, input, tally.members(), tally.hitAt5(), tally.ndcgAt5(), tally.recallAt20(),
                    tally.distinctTop5());
        }
    }

    @BeforeAll
    void load() throws Exception {
        deleteIndexes();
        new CopyRows(jdbc).clear();
        data = RecommendEvalData.read();
        beforeLoad(data);
        cache = new CachingEmbeddingModel(embeddingModel, modelName(), context.getBean(ChatbotIndexProperties.class).dimensions(),
                cacheDir());
        access = new RecommendEvalAccess(context, cache);
        RecommendEvalLoader loader = new RecommendEvalLoader(new RecommendRows(jdbc), access);
        loader.copies(RecommendEvalHoldout.hide(data));
        loader.vectors(data);
        grid = new RecommendEvalGrid(ConfigurationPropertySources.get(environment));
    }

    /** 다른 임베딩 모델로 벡터만 다시 넣는다(캐시 확인용) */
    void reloadVectors(EmbeddingModel model) {
        new RecommendEvalLoader(new RecommendRows(jdbc), new RecommendEvalAccess(context, model)).vectors(data);
    }

    @AfterAll
    void cleanUp() throws Exception {
        deleteIndexes();
        new CopyRows(jdbc).clear();
    }

    /** 조합마다 평가하고 표를 콘솔에 찍는다 */
    List<Row> evaluateAndPrint(Group group, List<Combo> combos, @Nullable String base) {
        List<Row> rows = evaluate(group, combos);
        System.out.println(table(group, combos, base, rows));
        return rows;
    }

    List<Row> evaluate(Group group, List<Combo> combos) {
        Map<Long, Long> lectureAnswers = RecommendEvalData.byRequester(data.lectureAnswers());
        Map<Long, Long> influencerAnswers = RecommendEvalData.byRequester(data.influencerAnswers());
        Map<Long, String> interests = new HashMap<>();
        for (Member member : data.members()) {
            interests.put(member.id(), member.interest());
        }
        List<Row> rows = new ArrayList<>();
        for (Combo combo : combos) {
            String change = grid.change(combo);
            if (combo.lecture()) {
                ChatbotLectureRecommendProperties top = grid.lecture(combo, RecommendMetrics.TOP);
                ChatbotLectureRecommendProperties pool = grid.lecture(combo, RecommendMetrics.POOL);
                for (LectureInput input : LectureInput.values()) {
                    Tally tally = new Tally();
                    for (long memberId : group.of(data.lectureSplit())) {
                        String interest = input == LectureInput.SIGNAL ? null : interests.get(memberId);
                        List<Pick> topPicks = access.lecture(top, memberId, interest);
                        List<Pick> poolPicks = access.lecture(pool, memberId, interest);
                        tally.add(lectureAnswers.get(memberId), topPicks, poolPicks);
                    }
                    rows.add(Row.of("강의", change, input.description(), tally));
                }
            }
            if (combo.influencer()) {
                ChatbotInfluencerRecommendProperties top = grid.influencer(combo, RecommendMetrics.TOP);
                ChatbotInfluencerRecommendProperties pool = grid.influencer(combo, RecommendMetrics.POOL);
                Tally tally = new Tally();
                for (long memberId : group.of(data.influencerSplit())) {
                    tally.add(influencerAnswers.get(memberId), access.influencer(top, memberId),
                            access.influencer(pool, memberId));
                }
                rows.add(Row.of("인플루언서", change, LectureInput.SIGNAL.description(), tally));
            }
        }
        return rows;
    }

    // 보정 기록 표에 그대로 옮기는 마크다운 표. 회차·가설·판단·메모는 사람이 채운다. 대상·입력·회원·서로 다른 수는 뒤에 붙인다
    private String table(Group group, List<Combo> combos, @Nullable String base, List<Row> rows) {
        Combo baseline = RecommendEvalGrid.combos(null, base).getFirst();
        StringBuilder out = new StringBuilder();
        out.append("\n=== 추천 가중치 평가: ").append(modelName()).append(" / ").append(group.description()).append(" / ")
                .append(LocalDate.now()).append(" ===\n");
        out.append("고정값(").append(RecommendEvalGrid.BASE_ENV).append("): ").append(base == null || base.isBlank() ? "없음" : base)
                .append('\n');
        out.append("임베딩 API로 보낸 글: ").append(cache.delegateTexts()).append("건(0이면 모두 캐시)\n");
        if (combos.getFirst().lecture()) {
            ChatbotLectureRecommendProperties lecture = grid.lecture(baseline, RecommendMetrics.TOP);
            out.append(String.format(Locale.ROOT, "강의 기준값: weights=%s, signal-limit=%d, interest-weight(α)=%s, "
                            + "popularity-weight(β)=%s%n", lecture.weights(), lecture.signalLimit(), lecture.interestWeight(),
                    lecture.popularityWeight()));
        }
        if (combos.getFirst().influencer()) {
            ChatbotInfluencerRecommendProperties influencer = grid.influencer(baseline, RecommendMetrics.TOP);
            out.append(String.format(Locale.ROOT, "인플루언서 기준값: partner-weight=%s, popularity-weight(β)=%s, popularity=%s%n",
                    influencer.partnerWeight(), influencer.popularityWeight(), influencer.popularity()));
        }
        out.append("| 회차 | 날짜 | 가설 | 바꾼 값 (전 → 후) | hit@5 | nDCG@5 | recall@20 | 판단 | 메모 | 대상 | 입력 | 회원 | "
                + "상위 5개의 서로 다른 수 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        String today = LocalDate.now().toString();
        for (Row row : rows) {
            out.append(String.format(Locale.ROOT, "|  | %s |  | %s | %.3f | %.3f | %.3f |  |  | %s | %s | %d | %d |%n", today,
                    row.change(), row.hitAt5(), row.ndcgAt5(), row.recallAt20(), row.target(), row.input(), row.members(),
                    row.distinctTop5()));
        }
        return out.toString();
    }

    private void deleteIndexes() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (IndexType type : IndexType.values()) {
            String name = context.getBean(ChatbotIndexProperties.class).nameOf(type);
            http.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + "/" + name)).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding());
        }
    }
}
