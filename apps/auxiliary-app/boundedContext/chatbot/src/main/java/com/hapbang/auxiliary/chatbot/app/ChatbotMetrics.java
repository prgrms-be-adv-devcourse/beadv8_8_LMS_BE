package com.hapbang.auxiliary.chatbot.app;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.domain.IndexType;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * 챗봇 지표(#715). 대화는 저장하지 않으므로 운영은 이 지표로 본다. 회원 ID·질문은 태그에 넣지 않는다.
 * <ul>
 *     <li>{@code hapbang.chatbot.requests{feature}}: 기능(진입점)별 사용 수. 대화 Tool 호출은 세지 않는다</li>
 *     <li>{@code hapbang.chatbot.tool.calls{tool}}: 대화 LLM이 부른 Tool 수(Tool 이름별)</li>
 *     <li>{@code hapbang.chatbot.faq.unmatched{feature}}: 기준값을 넘는 FAQ를 하나도 못 찾은 수(FAQ 매칭 실패)</li>
 *     <li>{@code hapbang.chatbot.faq.search.errors}: ES·임베딩 모델 호출 실패로 검색하지 못한 수</li>
 *     <li>{@code hapbang.chatbot.fallbacks{feature, reason}}: LLM 호출이 실패해 검색 결과·안내 문구·정해진 문장으로 답한 수</li>
 *     <li>{@code hapbang.chatbot.recommend.search.errors{feature}}: 추천 검색(ES·임베딩)이 실패해 인기순으로 대신 답한 수</li>
 *     <li>{@code hapbang.chatbot.rate.limit.rejected{feature}}: 회원별 호출 제한으로 429를 준 수</li>
 *     <li>{@code hapbang.chatbot.index.tasks{type, result}}: 색인 요청 실행기({@link IndexTaskRunner})가 처리한 문서 수</li>
 *     <li>{@code hapbang.chatbot.recommend.similarity{type, path}}: 추천 후보(벡터 검색 결과 중 코드 제외 규칙을 지난 것)의 유사도
 *     점수 분포(#1007). 경로({@link RecommendPath})마다 관심사 비율·풀 크기가 달라 나눠 센다. 0.05 간격 구간을 둔다.
 *     하한을 정하려고 보는 값이고 결과를 거르지 않는다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ChatbotMetrics {

    static final String INDEX_TASKS = "hapbang.chatbot.index.tasks";
    static final String RECOMMEND_SIMILARITY = "hapbang.chatbot.recommend.similarity";

    // 유사도(ES 코사인 점수 0~1)의 분포 구간: 0.05 간격
    private static final double[] SIMILARITY_BUCKETS = similarityBuckets();

    private final MeterRegistry registry;

    // 유사도 지표(태그 조합 → 지표). 후보마다 레지스트리를 다시 찾지 않게 둔다
    private final Map<String, DistributionSummary> similarity = new ConcurrentHashMap<>();

    public void request(Feature feature) {
        counter("hapbang.chatbot.requests", "기능별 챗봇 사용 수", "feature", feature.tag()).increment();
    }

    public void unmatched(Feature feature) {
        counter("hapbang.chatbot.faq.unmatched", "기준값을 넘는 FAQ를 찾지 못한 수", "feature", feature.tag()).increment();
    }

    public void searchError() {
        counter("hapbang.chatbot.faq.search.errors", "ES·임베딩 모델 호출 실패로 FAQ를 검색하지 못한 수").increment();
    }

    public void toolCall(String tool) {
        counter("hapbang.chatbot.tool.calls", "대화 LLM이 부른 Tool 수", "tool", tool).increment();
    }

    public void recommendSearchError(Feature feature) {
        counter("hapbang.chatbot.recommend.search.errors", "추천 검색(ES·임베딩) 실패로 인기순으로 답한 수",
                "feature", feature.tag()).increment();
    }

    public void fallback(Feature feature, FallbackReason reason) {
        counter("hapbang.chatbot.fallbacks", "LLM 호출이 실패해 정해진 답으로 대신한 수",
                "feature", feature.tag(), "reason", reason.tag()).increment();
    }

    public void rateLimited(Feature feature) {
        counter("hapbang.chatbot.rate.limit.rejected", "회원별 호출 제한으로 거절한 수", "feature", feature.tag()).increment();
    }

    /** 색인 요청 실행기가 처리한 문서 수. 0건은 세지 않는다 */
    void indexTasks(IndexType type, IndexTaskRunner.Result result, int amount) {
        if (amount > 0) {
            counter(INDEX_TASKS, "챗봇 색인 요청을 처리한 문서 수", "type", type.name(), "result", result.tag())
                    .increment(amount);
        }
    }

    /** 추천 후보 하나의 유사도 점수(#1007). 하한을 정하려고 분포를 본다. 요청마다 후보 수만큼 불려 태그 조합별로 한 번만 만든다 */
    void recommendSimilarity(IndexType type, RecommendPath path, double score) {
        similarity.computeIfAbsent(type.name() + "/" + path.tag(), key -> DistributionSummary.builder(RECOMMEND_SIMILARITY)
                .description("추천 후보의 유사도 점수")
                .tags("type", type.name(), "path", path.tag())
                .serviceLevelObjectives(SIMILARITY_BUCKETS)
                .register(registry)).record(score);
    }

    private static double[] similarityBuckets() {
        double[] buckets = new double[20];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = (i + 1) / 20.0;
        }
        return buckets;
    }

    private Counter counter(String name, String description, String... tags) {
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }

    /** 지표 태그 값으로 쓰는 enum. 값은 이름의 소문자다 */
    public interface Tagged {

        String name();

        default String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 지표의 기능 태그 */
    public enum Feature implements Tagged {
        FAQ_LIST,
        FAQ_SEARCH,
        FAQ_CHAT,
        FAQ_STREAM,
        /** 강의 추천 위젯 API */
        LECTURE_RECOMMEND,
        /** 인플루언서 추천 위젯 API */
        INFLUENCER_RECOMMEND,
        /** 챗봇 "강의 추천받기" 버튼(리랭킹 + 이유) */
        LECTURE_BUTTON,
        /** 챗봇 "인플루언서 추천받기" 버튼(리랭킹 + 이유) */
        INFLUENCER_BUTTON,
        /** 버튼 경로의 관심사 쿼리 확장(LLM 실패 지표만) */
        QUERY_EXPANSION,
        /** 자유 질문(한 번에 받기) */
        CHAT,
        /** 자유 질문(스트림) */
        CHAT_STREAM
    }

    /** 추천 경로 태그(#1007). 경로마다 관심사 비율·후보 풀 크기가 다르다 */
    public enum RecommendPath implements Tagged {
        /** 추천 위젯 API(관심사 비율 {@code interest-weight}, 풀 = 추천 수) */
        WIDGET,
        /** 챗봇 추천 버튼(관심사 비율 {@code interest-weight}, 큰 풀에서 LLM이 고름) */
        BUTTON,
        /** 자유 질문 대화의 Tool(강의는 관심사 비율 {@code chat-interest-weight}) */
        CHAT
    }

    /** fallback 이유 */
    public enum FallbackReason implements Tagged {
        /** 모델을 끈 실행(설정) */
        MODEL_OFF,
        /** 회로가 열려 LLM을 부르지 않음 */
        CIRCUIT_OPEN,
        /** 동시 LLM 호출 상한에 걸림 */
        BUSY,
        /** 시간 초과 */
        TIMEOUT,
        /** 모델 호출 오류(쓸 만한 답이 하나도 없는 경우 포함) */
        ERROR,
        /** 모델은 답했지만 정해진 형식(JSON)으로 읽을 수 없음. 회로 실패로 세지 않는다 */
        FORMAT,
        /** 한 요청의 Tool 호출이 상한을 넘음. 회로 실패로 세지 않는다 */
        TOOL_LIMIT;

        /**
         * 호출을 했는데 실패한 것인지(오류·시간 초과·형식 오류·Tool 상한). 경고 로그로 남긴다. 모델을 끈 실행·회로 열림·동시 상한은
         * 부르지 않은 것이라 요청마다 남기지 않는다(지표로 본다)
         */
        public boolean attempted() {
            return this == ERROR || this == TIMEOUT || this == FORMAT || this == TOOL_LIMIT;
        }
    }
}
