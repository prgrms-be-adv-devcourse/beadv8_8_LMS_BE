package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.domain.FaqCategory;
import com.hapbang.auxiliary.chatbot.domain.FaqRepository;
import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;
import com.hapbang.auxiliary.global.ratelimit.KeyedRateLimiter;

import reactor.core.publisher.Flux;

/**
 * FAQ 챗봇 API의 진입점(#715). 역할 검사·회원별 호출 제한·사용 지표를 여기서 한다.
 * <ul>
 *     <li>권한(11/06 결정): 로그인 회원 전체(USER 이상). 비회원은 공통 SecurityConfig가 401로 막는다.</li>
 *     <li>호출 제한: FAQ 검색과 대화(한 번에·스트림)를 합쳐 회원마다 센다. FAQ 목록(LLM·ES 없음)은 세지 않는다.</li>
 * </ul>
 */
@Service
public class ChatbotFaqFacade {

    private final FaqRepository faqRepository;
    private final FaqSearchService searchService;
    private final FaqChatService chatService;
    private final KeyedRateLimiter rateLimiter;
    private final ChatbotMetrics metrics;

    ChatbotFaqFacade(FaqRepository faqRepository, FaqSearchService searchService, FaqChatService chatService,
            @Qualifier("chatbotFaqRateLimiter") KeyedRateLimiter rateLimiter, ChatbotMetrics metrics) {
        this.faqRepository = faqRepository;
        this.searchService = searchService;
        this.chatService = chatService;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    /** FAQ 모아보기. 분류가 없으면 전체(분류·시드 순서) */
    @PreAuthorize(ChatbotRoles.MEMBER)
    @Transactional(readOnly = true)
    public List<FaqView> listFaqs(@Nullable FaqCategory category) {
        metrics.request(Feature.FAQ_LIST);
        var faqs = category == null ? faqRepository.findAllByOrderBySortOrderAsc()
                : faqRepository.findByCategoryOrderBySortOrderAsc(category);
        return faqs.stream()
                .map(faq -> new FaqView(faq.getCode(), faq.getCategory(), faq.getQuestion(), faq.getAnswer()))
                .toList();
    }

    /** FAQ 벡터 검색(LLM 없음) */
    @PreAuthorize(ChatbotRoles.MEMBER)
    public List<FaqHit> searchFaqs(long userId, String query) {
        acquire(userId, Feature.FAQ_SEARCH);
        List<FaqHit> hits = searchService.search(query);
        if (hits.isEmpty()) {
            metrics.unmatched(Feature.FAQ_SEARCH);
        }
        return hits;
    }

    @PreAuthorize(ChatbotRoles.MEMBER)
    public FaqAnswer chat(long userId, String message) {
        acquire(userId, Feature.FAQ_CHAT);
        return chatService.chat(userId, message);
    }

    /** 호출 제한은 스트림을 만들기 전에 센다. 넘으면 SSE를 열지 않고 429다 */
    @PreAuthorize(ChatbotRoles.MEMBER)
    public Flux<FaqStreamEvent> stream(long userId, String message) {
        acquire(userId, Feature.FAQ_STREAM);
        return chatService.stream(userId, message);
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

    /** FAQ 모아보기 항목 */
    public record FaqView(String code, FaqCategory category, String question, String answer) {
    }
}
