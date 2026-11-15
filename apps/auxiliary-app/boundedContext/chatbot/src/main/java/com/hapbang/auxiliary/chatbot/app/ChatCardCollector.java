package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import com.hapbang.auxiliary.chatbot.app.dto.ChatCardsResDto;

/**
 * 자유 질문 요청 하나의 추천 카드 수집기 (#720). 대화가 {@link ChatToolContext}로 Tool에 넘긴다. 스트림은 다른 스레드에서 Tool을
 * 부르므로 스레드 안전하다. 같은 Tool을 한 요청에서 다시 부르면 나중 결과가 남는다. 부른 Tool 이름(로그용)도 모은다.
 */
public final class ChatCardCollector {

    private final AtomicReference<ChatCardsResDto.@Nullable LectureCards> lectures = new AtomicReference<>();
    private final AtomicReference<ChatCardsResDto.@Nullable InfluencerCards> influencers = new AtomicReference<>();
    private final ConcurrentLinkedQueue<String> calledTools = new ConcurrentLinkedQueue<>();

    void lectures(ChatCardsResDto.LectureCards cards) {
        lectures.set(cards);
    }

    void influencers(ChatCardsResDto.InfluencerCards cards) {
        influencers.set(cards);
    }

    void called(String tool) {
        calledTools.add(tool);
    }

    public ChatCardsResDto cards() {
        return new ChatCardsResDto(lectures.get(), influencers.get());
    }

    /** 부른 Tool 이름(부른 순서). 로그에 남긴다 */
    public List<String> calledTools() {
        return new ArrayList<>(calledTools);
    }
}
