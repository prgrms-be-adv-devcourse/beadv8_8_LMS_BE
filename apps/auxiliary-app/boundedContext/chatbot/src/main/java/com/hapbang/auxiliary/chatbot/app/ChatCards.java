package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.auxiliary.chatbot.app.dto.InfluencerItemResDto;
import com.hapbang.auxiliary.chatbot.app.dto.LectureRecommendResDto;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 자유 질문에서 추천 Tool이 만든 카드 (#720). FE 카드는 이 목록만 그리고, LLM 글은 설명으로만 쓴다
 * (스트림 글에서는 후보 ID를 검사할 수 없다). 카드는 코드가 만든 것이라 LLM이 실패해도 맞다.
 *
 * @param lectures    강의 추천 Tool이 고른 카드. Tool을 부르지 않았으면 없다
 * @param influencers 인플루언서 추천 Tool이 고른 카드(회원 ID·함께 출연한 이력 포함, LLM에는 가지 않는다). 부르지 않았으면 없다
 */
@Schema(description = "추천 카드. 이번 요청에서 추천 Tool을 부른 종류만 온다")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCards(@Nullable LectureCards lectures, @Nullable InfluencerCards influencers) {

    public boolean isEmpty() {
        return lectures == null && influencers == null;
    }

    /** 강의 카드. 근거 코드는 위젯 API와 같다 */
    public record LectureCards(RecommendBasis basis, List<LectureRecommendResDto.Item> items) {
    }

    /** 인플루언서 카드. 근거 코드는 위젯 API와 같다 */
    public record InfluencerCards(RecommendBasis basis, List<InfluencerItemResDto> items) {
    }

    /**
     * 요청 하나의 카드 수집기. 대화가 {@link ChatToolContext}로 Tool에 넘긴다. 스트림은 다른 스레드에서 Tool을 부르므로 스레드 안전하다.
     * 같은 Tool을 한 요청에서 다시 부르면 나중 결과가 남는다. 부른 Tool 이름(로그용)도 모은다.
     */
    public static final class Collector {

        private final AtomicReference<@Nullable LectureCards> lectures = new AtomicReference<>();
        private final AtomicReference<@Nullable InfluencerCards> influencers = new AtomicReference<>();
        private final ConcurrentLinkedQueue<String> calledTools = new ConcurrentLinkedQueue<>();

        void lectures(LectureCards cards) {
            lectures.set(cards);
        }

        void influencers(InfluencerCards cards) {
            influencers.set(cards);
        }

        void called(String tool) {
            calledTools.add(tool);
        }

        public ChatCards cards() {
            return new ChatCards(lectures.get(), influencers.get());
        }

        /** 부른 Tool 이름(부른 순서). 로그에 남긴다 */
        public List<String> calledTools() {
            return new ArrayList<>(calledTools);
        }
    }
}
