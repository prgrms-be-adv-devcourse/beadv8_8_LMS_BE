package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;
import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;

import reactor.core.publisher.Flux;

/**
 * 자유 질문 API의 진입점 (#720). 역할 검사·회원별 호출 제한·사용 지표를 여기서 한다.
 * <ul>
 *     <li>로그인 회원 전체(USER 이상). 비회원은 공통 SecurityConfig가 401로 막는다.</li>
 *     <li>호출 제한은 FAQ 검색·대화(#715)의 제한을 함께 쓴다(회원 ID 키). 대화 한 번(Tool 호출·임베딩 포함)을 한 번으로 센다.
 *     스트림은 SSE를 열기 전에 센다(넘으면 429).</li>
 *     <li>트랜잭션을 열지 않는다(LLM·ES 호출 사이에 DB 연결을 쥐지 않는다).</li>
 * </ul>
 */
@Service
public class ChatbotChatFacade {

    private final ChatbotConversationService conversationService;
    private final KeyedRateLimiter rateLimiter;
    private final ChatbotMetrics metrics;

    ChatbotChatFacade(ChatbotConversationService conversationService,
            @Qualifier("chatbotFaqRateLimiter") KeyedRateLimiter rateLimiter, ChatbotMetrics metrics) {
        this.conversationService = conversationService;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @PreAuthorize(ChatbotRoles.MEMBER)
    public ChatAnswer chat(long userId, String message, List<ChatHistory.Entry> history) {
        acquire(userId, Feature.CHAT);
        return conversationService.chat(userId, message, history);
    }

    @PreAuthorize(ChatbotRoles.MEMBER)
    public Flux<ChatStreamEvent> stream(long userId, String message, List<ChatHistory.Entry> history) {
        acquire(userId, Feature.CHAT_STREAM);
        return conversationService.stream(userId, message, history);
    }

    private void acquire(long userId, Feature feature) {
        try {
            rateLimiter.acquire(String.valueOf(userId));
        } catch (RateLimitExceededException e) {
            metrics.rateLimited(feature);
            throw e;
        }
        metrics.request(feature);
    }
}
