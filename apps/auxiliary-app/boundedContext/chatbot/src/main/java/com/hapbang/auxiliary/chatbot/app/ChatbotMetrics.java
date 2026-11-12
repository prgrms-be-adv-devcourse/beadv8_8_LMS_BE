package com.hapbang.auxiliary.chatbot.app;

import java.util.Locale;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
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
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ChatbotMetrics {

    private final MeterRegistry registry;

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
        TOOL_LIMIT
    }
}
