package com.hapbang.auxiliary.chatbot.llm.recommend;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatModel;

import com.hapbang.auxiliary.chatbot.app.RecommendEvalAccess;
import com.hapbang.auxiliary.chatbot.app.RecommendEvalAccess.ButtonResult;
import com.hapbang.auxiliary.chatbot.app.RecommendEvalAccess.Buttons;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties.Taste;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Lecture;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Member;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalGrid.Combo;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvaluation.Group;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendMetrics.Pick;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 버튼 경로 리랭킹 평가 본체(#867 2단계, #948). 버튼 경로(쿼리 확장 → 맞춤 후보 풀 → LLM 리랭킹 + 이유)를 운영 클래스 그대로 부르고,
 * <b>같은 풀의 코드 순서</b>와 비교한다. 데이터·색인·캐시는 가중치 평가({@link RecommendEvaluation})가 넣은 것을 쓴다.
 * <ul>
 *     <li>가중치는 운영 설정 파일에서 읽는다({@link RecommendEvalGrid#withProdProfile}). 리랭킹 설정의 시간 상한·이유 길이·소개 길이·확장 수는
 *     테스트 컨텍스트의 값(운영 기본값과 같다)이고, 방식마다 취향·온도·쿼리 확장만 바꾼다.</li>
 *     <li>방식({@link Mode})마다 정한 대상만 잰다. 공개 취향 방식에서 강의 "신호만"은 넣을 취향이 없어(평가의 강의 회원은 일반 회원) 리랭킹하지
 *     않으므로 잴 것이 없다(코드 순서와 같다, {@code RecommendTasteButtonTest}가 고정한다). 인플루언서 회원은 분야가 있는 인플루언서라
 *     공개 프로필(분야·자기소개)로 리랭킹한다.</li>
 *     <li>지표: hit@5(회차 평균·범위), 같은 풀의 코드 순서 hit@5, 끌어올림(코드 5개 밖 → 5개 안)·밀어냄(코드 5개 안 → 밖), fallback,
 *     후보 밖 순번(LLM에 보여 준 후보 수 기준), 이유 길이 상한을 넘은 이유, 리랭킹 지연·토큰. 읽어 볼 10명(맞힘 5, 못 맞힘 5)과 비용을 찍는다.</li>
 *     <li>회원은 {@value #THREADS}명씩 함께 돈다(앱 LLM 동시 상한과 같게). 쿼리 확장 답은 회원 안에서 다시 받지 않는다({@link EvalChatModel}).</li>
 * </ul>
 */
final class RerankEvaluation {

    static final int THREADS = 4;
    static final String DEFAULT_MODEL = "gpt-4o-mini";

    private static final int SAMPLES = 5;
    // USD, 100만 토큰당 입력·출력(2026-11-15 OpenAI 가격표)
    private static final Map<String, double[]> PRICES = Map.of("gpt-4o-mini", new double[]{0.15, 0.60},
            "gpt-4.1", new double[]{2.00, 8.00});
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 비교할 방식 */
    enum Mode {

        NONE("none", "리랭킹, 취향 없음(지금 운영, #720)", Taste.NONE, null, true,
                List.of(Target.LECTURE_SIGNAL, Target.LECTURE_INTEREST, Target.INFLUENCER)),
        PUBLIC("public", "리랭킹, 공개 취향(#948)", Taste.PUBLIC, null, true, List.of(Target.LECTURE_INTEREST, Target.INFLUENCER)),
        PUBLIC_T0("public-t0", "리랭킹, 공개 취향, 온도 0", Taste.PUBLIC, 0.0, true,
                List.of(Target.LECTURE_INTEREST, Target.INFLUENCER)),
        PUBLIC_NO_EXPANSION("public-noexp", "리랭킹, 공개 취향, 쿼리 확장 없음", Taste.PUBLIC, null, false,
                List.of(Target.LECTURE_INTEREST));

        private final String key;
        private final String description;
        private final Taste taste;
        private final @Nullable Double temperature;
        private final boolean expansion;
        private final List<Target> targets;

        Mode(String key, String description, Taste taste, @Nullable Double temperature, boolean expansion,
                List<Target> targets) {
            this.key = key;
            this.description = description;
            this.taste = taste;
            this.temperature = temperature;
            this.expansion = expansion;
            this.targets = targets;
        }

        String description() {
            return description;
        }

        // 테스트 컨텍스트의 리랭킹 설정(운영 기본값)에서 켜기·모델·풀 크기·취향·온도·확장만 바꾼다
        ChatbotRecommendLlmProperties properties(ChatbotRecommendLlmProperties base, String model, int poolSize) {
            ChatbotRecommendLlmProperties.Rerank rerank = base.rerank();
            ChatbotRecommendLlmProperties.QueryExpansion expansionBase = base.queryExpansion();
            return new ChatbotRecommendLlmProperties(
                    new ChatbotRecommendLlmProperties.Rerank(true, model, rerank.timeout(), poolSize, rerank.reasonMaxLength(),
                            rerank.descriptionMaxLength(), taste, temperature),
                    new ChatbotRecommendLlmProperties.QueryExpansion(expansionBase.model(), expansionBase.timeout(),
                            expansion ? expansionBase.maxExpansions() : 0));
        }

        /** @param text 쉼표로 나눈 방식 키. 비면 모두 */
        static List<Mode> parse(@Nullable String text) {
            if (text == null || text.isBlank()) {
                return List.of(values());
            }
            List<Mode> modes = new ArrayList<>();
            for (String part : text.split(",")) {
                Mode found = null;
                for (Mode mode : values()) {
                    if (mode.key.equals(part.strip())) {
                        found = mode;
                    }
                }
                if (found == null) {
                    throw new IllegalArgumentException("모르는 리랭킹 방식입니다: " + part.strip()
                            + " (none, public, public-t0, public-noexp)");
                }
                modes.add(found);
            }
            return modes;
        }
    }

    /** 잴 대상(추천 종류 + 입력) */
    enum Target {

        LECTURE_SIGNAL("강의", "신호만"),
        LECTURE_INTEREST("강의", "신호+관심사"),
        INFLUENCER("인플루언서", "신호만");

        private final String subject;
        private final String input;

        Target(String subject, String input) {
            this.subject = subject;
            this.input = input;
        }

        String description() {
            return subject + " " + input;
        }

        /** 요청 회원 → 숨긴 정답 */
        Map<Long, Long> answers(RecommendEvalData data) {
            return RecommendEvalData.byRequester(this == INFLUENCER ? data.influencerAnswers() : data.lectureAnswers());
        }

        List<Long> members(RecommendEvalData data, Group group) {
            return group.of(this == INFLUENCER ? data.influencerSplit() : data.lectureSplit());
        }

        /** 버튼 한 번. 관심사는 "신호+관심사"에만 준다 */
        ButtonResult press(Buttons buttons, long member, Map<Long, String> interests) {
            return switch (this) {
                case LECTURE_SIGNAL -> buttons.lecture(member, null);
                case LECTURE_INTEREST -> buttons.lecture(member, interests.get(member));
                case INFLUENCER -> buttons.influencer(member);
            };
        }
    }

    /**
     * 실행 설정.
     *
     * @param base  운영 가중치 위에 더 덮을 값({@link RecommendEvalGrid} 형식). 비면 운영 값 그대로
     * @param model 리랭킹 모델. 비우면 공급자 설정
     * @param limit 앞에서 몇 명만(0이면 모두, 시험 실행)
     */
    record Settings(List<Mode> modes, int runs, int limit, String model, int poolSize, String base) {
    }

    /**
     * 회원 한 명의 결과.
     *
     * @param codeRank   정답이 같은 풀(코드 순서)의 몇 번째인지(1부터, 없으면 0)
     * @param codeHit    리랭킹하지 않았으면 5개 안이었다(풀에 없는 정답은 인기순 채움으로 들어온 경우만 둘 다 맞힌 것으로 본다)
     * @param hit        버튼 경로의 5개 안에 정답이 있다
     * @param picks      LLM이 답한 순번 수
     * @param outOfPool  그중 LLM에 보여 준 후보 밖 순번 수
     * @param reasonsCut 그중 이유가 길이 상한을 넘은 수
     */
    record Outcome(long member, long answer, int codeRank, boolean codeHit, boolean hit, boolean fallback, int picks,
            int outOfPool, int reasonsCut, List<Long> top, Map<Long, String> reasons) {
    }

    /** 표 한 줄의 합계(검증용) */
    record Line(Mode mode, Target target, int members, int runs, double hitAt5, double codeHitAt5, int fallbacks, int picks,
            int outOfPool, int reasonsCut) {
    }

    /** @param text 콘솔에 찍을 표·읽어 볼 회원·비용 */
    record Report(String text, List<Line> lines) {
    }

    private final RecommendEvalData data;
    private final RecommendEvalAccess access;
    private final RecommendEvalGrid grid;
    private final CachingEmbeddingModel cache;
    private final EvalChatModel chatModel;
    private final ChatbotRecommendLlmProperties llmBase;

    /**
     * @param grid    운영 프로필 값을 앞에 둔 조합기({@link RecommendEvalGrid#withProdProfile})
     * @param llmBase 테스트 컨텍스트의 리랭킹 설정(운영 기본값)
     */
    RerankEvaluation(RecommendEvalData data, RecommendEvalAccess access, RecommendEvalGrid grid, CachingEmbeddingModel cache,
            ChatModel chatModel, ChatbotRecommendLlmProperties llmBase) {
        this.data = data;
        this.access = access;
        this.grid = grid;
        this.cache = cache;
        this.chatModel = new EvalChatModel(chatModel);
        this.llmBase = llmBase;
    }

    Report run(Group group, Settings settings) throws InterruptedException, ExecutionException {
        Combo combo = RecommendEvalGrid.combos(null, settings.base()).getFirst();
        ChatbotLectureRecommendProperties lectureProperties = grid.lecture(combo, RecommendMetrics.TOP);
        ChatbotInfluencerRecommendProperties influencerProperties = grid.influencer(combo, RecommendMetrics.TOP);
        String modelName = settings.model().isBlank() ? DEFAULT_MODEL : settings.model();

        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%n=== 리랭킹 평가: %s / %s / %s ===%n", modelName, group.description(),
                LocalDate.now()));
        out.append(String.format(Locale.ROOT, "가중치: 운영(application-prod.yaml)%s, 강의 β=%s, 인플루언서 β=%s%n",
                settings.base().isBlank() ? "" : " + " + settings.base(), lectureProperties.popularityWeight(),
                influencerProperties.popularityWeight()));
        out.append(String.format(Locale.ROOT, "풀 크기: %d, 회차: %d%s%n", settings.poolSize(), settings.runs(),
                settings.limit() > 0 ? ", 앞 " + settings.limit() + "명만" : ""));
        out.append("| 방식 | 대상 | 회원 | hit@5 평균 | 범위 | 같은 풀 코드 순서 hit@5 | 끌어올림 | 밀어냄 | fallback | 후보 밖 순번 "
                + "| 이유 상한 넘음 | 리랭킹 지연 평균·최대(ms) | 리랭킹 토큰 평균(입력/출력) |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<Line> lines = new ArrayList<>();
        Map<Mode, List<Outcome>> interestRuns = new LinkedHashMap<>();
        long promptTokens = 0;
        long completionTokens = 0;
        long llmCalls = 0;
        for (Mode mode : settings.modes()) {
            Buttons buttons = access.buttons(lectureProperties, influencerProperties,
                    mode.properties(llmBase, settings.model(), settings.poolSize()), chatModel, data.fieldNames());
            for (Target target : mode.targets) {
                List<List<Outcome>> results = new ArrayList<>();
                for (int r = 0; r < settings.runs(); r++) {
                    results.add(evaluate(buttons, target, members(group, target, settings.limit())));
                }
                List<EvalChatModel.Call> calls = chatModel.drainCalls();
                for (EvalChatModel.Call call : calls) {
                    promptTokens += call.promptTokens();
                    completionTokens += call.completionTokens();
                }
                llmCalls += calls.size();
                Line line = line(mode, target, results);
                lines.add(line);
                out.append(row(line, results, calls));
                if (target == Target.LECTURE_INTEREST) {
                    interestRuns.put(mode, results.getFirst());
                }
            }
        }
        out.append(String.format(Locale.ROOT, "%nLLM 호출 %d회, 토큰 입력 %d / 출력 %d, 비용 약 $%.2f(쿼리 확장 포함, 가격표 기준 추정)%n",
                llmCalls, promptTokens, completionTokens, cost(modelName, promptTokens, completionTokens)));
        out.append("임베딩 API로 보낸 글: ").append(cache.delegateTexts()).append("건(쿼리 확장 문장이 새로 생기면 늘어난다)\n");
        out.append(samples(interestRuns));
        return new Report(out.toString(), lines);
    }

    private List<Outcome> evaluate(Buttons buttons, Target target, List<Long> members)
            throws InterruptedException, ExecutionException {
        Map<Long, Long> answers = target.answers(data);
        Map<Long, String> interests = new HashMap<>();
        for (Member member : data.members()) {
            interests.put(member.id(), member.interest());
        }
        int reasonMax = llmBase.rerank().reasonMaxLength();
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (long member : members) {
                futures.add(executor.submit(() -> {
                    chatModel.startMember();
                    ButtonResult result = target.press(buttons, member, interests);
                    EvalChatModel.RerankAnswer answer = result.fallback() ? null : chatModel.lastRerank();
                    return outcome(member, answers.get(member), result, answer, reasonMax);
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    static Outcome outcome(long member, long answer, ButtonResult result, EvalChatModel.@Nullable RerankAnswer rerank,
            int reasonMax) {
        int codeRank = result.pool().indexOf(answer) + 1;
        List<Long> top = new ArrayList<>();
        boolean fromFill = false;
        for (Pick pick : result.top()) {
            top.add(pick.id());
            fromFill |= pick.id() == answer && !pick.personalized();
        }
        boolean hit = top.contains(answer);
        boolean codeHit = codeRank >= 1 && codeRank <= RecommendMetrics.TOP || codeRank == 0 && fromFill;
        int picks = 0;
        int outOfPool = 0;
        int reasonsCut = 0;
        if (rerank != null) {
            for (JsonNode pick : JSON.readTree(rerank.output()).path("picks")) {
                picks++;
                int candidate = pick.path("candidate").asInt(0);
                if (candidate < 1 || candidate > rerank.shown()) {
                    outOfPool++;
                }
                String reason = pick.path("reason").asString("");
                if (reason.codePointCount(0, reason.length()) > reasonMax) {
                    reasonsCut++;
                }
            }
        }
        return new Outcome(member, answer, codeRank, codeHit, hit, result.fallback(), picks, outOfPool, reasonsCut, top,
                result.reasons());
    }

    private static Line line(Mode mode, Target target, List<List<Outcome>> results) {
        int members = results.getFirst().size();
        double hits = 0;
        double codeHits = 0;
        int fallbacks = 0;
        int picks = 0;
        int outOfPool = 0;
        int reasonsCut = 0;
        for (List<Outcome> run : results) {
            for (Outcome outcome : run) {
                hits += outcome.hit() ? 1 : 0;
                codeHits += outcome.codeHit() ? 1 : 0;
                fallbacks += outcome.fallback() ? 1 : 0;
                picks += outcome.picks();
                outOfPool += outcome.outOfPool();
                reasonsCut += outcome.reasonsCut();
            }
        }
        int total = Math.max(1, members * results.size());
        return new Line(mode, target, members, results.size(), hits / total, codeHits / total, fallbacks, picks, outOfPool,
                reasonsCut);
    }

    private static String row(Line line, List<List<Outcome>> results, List<EvalChatModel.Call> calls) {
        double min = 1;
        double max = 0;
        int lifted = 0;
        int pushed = 0;
        for (List<Outcome> run : results) {
            int hits = 0;
            for (Outcome outcome : run) {
                hits += outcome.hit() ? 1 : 0;
                lifted += !outcome.codeHit() && outcome.hit() ? 1 : 0;
                pushed += outcome.codeHit() && !outcome.hit() ? 1 : 0;
            }
            double rate = line.members() == 0 ? 0 : (double) hits / line.members();
            min = Math.min(min, rate);
            max = Math.max(max, rate);
        }
        long rerankCalls = 0;
        long millis = 0;
        long maxMillis = 0;
        long prompt = 0;
        long completion = 0;
        for (EvalChatModel.Call call : calls) {
            if (call.rerank()) {
                rerankCalls++;
                millis += call.millis();
                maxMillis = Math.max(maxMillis, call.millis());
                prompt += call.promptTokens();
                completion += call.completionTokens();
            }
        }
        return String.format(Locale.ROOT,
                "| %s | %s | %d | %.3f | %.3f~%.3f | %.3f | %.1f | %.1f | %d/%d | %d/%d | %d/%d | %d·%d | %d/%d |%n",
                line.mode().description(), line.target().description(), line.members(), line.hitAt5(), min, max,
                line.codeHitAt5(), (double) lifted / line.runs(), (double) pushed / line.runs(), line.fallbacks(),
                line.members() * line.runs(), line.outOfPool(), line.picks(), line.reasonsCut(), line.picks(),
                rerankCalls == 0 ? 0 : millis / rerankCalls, maxMillis, rerankCalls == 0 ? 0 : prompt / rerankCalls,
                rerankCalls == 0 ? 0 : completion / rerankCalls);
    }

    // 사람이 읽어 볼 회원: 강의 "신호+관심사" 첫 회차에서 맞힌 5명, 못 맞힌 5명. 공개 취향 방식이 있으면 그것, 없으면 처음 잰 방식
    private String samples(Map<Mode, List<Outcome>> interestRuns) {
        if (interestRuns.isEmpty()) {
            return "";
        }
        Mode mode = interestRuns.containsKey(Mode.PUBLIC) ? Mode.PUBLIC : interestRuns.keySet().iterator().next();
        Map<Long, Lecture> lectures = new HashMap<>();
        for (Lecture lecture : data.lectures()) {
            lectures.put(lecture.id(), lecture);
        }
        Map<Long, Member> members = new HashMap<>();
        for (Member member : data.members()) {
            members.put(member.id(), member);
        }
        Map<String, String> fields = data.fieldNames();
        Map<String, List<Outcome>> groups = new LinkedHashMap<>();
        groups.put("맞힘", new ArrayList<>());
        groups.put("못 맞힘", new ArrayList<>());
        for (Outcome outcome : interestRuns.get(mode)) {
            List<Outcome> group = groups.get(outcome.hit() ? "맞힘" : "못 맞힘");
            if (group.size() < SAMPLES) {
                group.add(outcome);
            }
        }
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%n읽어 볼 회원 (%s, 강의 신호+관심사, 1회차)%n",
                mode.description()));
        for (Map.Entry<String, List<Outcome>> group : groups.entrySet()) {
            for (Outcome outcome : group.getValue()) {
                Member member = members.get(outcome.member());
                Lecture answer = lectures.get(outcome.answer());
                List<String> taste = new ArrayList<>();
                for (String field : member.tasteFields()) {
                    taste.add(fields.get(field));
                }
                out.append(String.format(Locale.ROOT, "%n[%s] 회원 %d · 취향 %s · 관심사 \"%s\"%n  정답: %s(%s), 코드 순서 %s%n",
                        group.getKey(), member.id(), taste, member.interest(), answer.title(), fields.get(answer.field()),
                        outcome.codeRank() == 0 ? "풀 밖" : outcome.codeRank() + "위"));
                int rank = 1;
                for (long id : outcome.top()) {
                    Lecture lecture = lectures.get(id);
                    out.append(String.format(Locale.ROOT, "  %d. %s(%s)%s — %s%n", rank++, lecture.title(),
                            fields.get(lecture.field()), id == outcome.answer() ? " ★정답" : "", outcome.reasons().get(id)));
                }
            }
        }
        return out.toString();
    }

    private List<Long> members(Group group, Target target, int limit) {
        List<Long> all = target.members(data, group);
        return limit > 0 && limit < all.size() ? all.subList(0, limit) : all;
    }

    private static double cost(String model, long prompt, long completion) {
        double[] price = PRICES.getOrDefault(model, new double[]{0, 0});
        return (prompt * price[0] + completion * price[1]) / 1_000_000;
    }
}
