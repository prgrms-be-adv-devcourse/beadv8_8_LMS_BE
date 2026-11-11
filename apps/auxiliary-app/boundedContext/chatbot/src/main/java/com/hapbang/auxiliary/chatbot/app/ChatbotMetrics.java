package com.hapbang.auxiliary.chatbot.app;

import java.util.Locale;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * 챗봇 지표(#715). 대화는 저장하지 않으므로 운영은 이 지표로 본다. 회원 ID·질문은 태그에 넣지 않는다.
 * <ul>
 *     <li>{@code hapbang.chatbot.requests{feature}}: 기능별 사용 수</li>
 *     <li>{@code hapbang.chatbot.faq.unmatched{feature}}: 기준값을 넘는 FAQ를 하나도 못 찾은 수(FAQ 매칭 실패)</li>
 *     <li>{@code hapbang.chatbot.faq.search.errors}: ES·임베딩 모델 호출 실패로 검색하지 못한 수</li>
 *     <li>{@code hapbang.chatbot.fallbacks{feature, reason}}: LLM 대신 검색 결과·안내 문구로 답한 수</li>
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

    public void fallback(Feature feature, FallbackReason reason) {
        counter("hapbang.chatbot.fallbacks", "LLM 대신 검색 결과·안내 문구로 답한 수",
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
        INFLUENCER_RECOMMEND
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
        /** 모델 호출 오류 */
        ERROR
    }
}
