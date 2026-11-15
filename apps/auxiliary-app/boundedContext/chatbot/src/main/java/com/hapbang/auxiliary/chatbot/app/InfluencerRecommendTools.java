package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.dto.ChatCardsResDto;
import com.hapbang.auxiliary.chatbot.app.dto.InfluencerItemResDto;
import com.hapbang.auxiliary.chatbot.app.dto.InfluencerRecommendResDto;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;

import lombok.RequiredArgsConstructor;

/**
 * 모델이 부르는 인플루언서 추천 Tool (#719, 자유 질문 #720이 등록한다).
 * <ul>
 *     <li><b>회원 ID는 {@link ToolContext}의 {@link ChatToolContext#MEMBER_ID}에서만 꺼낸다.</b> Tool 인자로 받지 않는다(모델이 다른 회원 ID를 넣을 수 없다).
 *     ToolContext 인자는 Tool 스키마에서 빠진다. 없으면 호출 쪽 잘못이라 거절한다.</li>
 *     <li>관심사 문장은 대화에서 모델이 채운다(예: "롤 방송"). 있으면 그 문장으로 찾고, 없으면 위젯과 같다.
 *     {@code interest-max-length}를 넘으면 자른다(임베딩 비용 상한). 대화 전체의 호출 제한은 자유 질문 진입점이 건다.</li>
 *     <li><b>카드와 LLM에 가는 결과를 나눈다.</b> 카드(회원 ID·함께 출연한 이력 {@code PAST_PARTNER} 포함)는 수집기
 *     ({@link ChatToolContext#CARDS})에 담아 FE로 따로 보낸다. 대화 LLM에는 분야·공개 통계·나머지 이유 코드만 준다
 *     (회원 ID와 요청 회원의 출연 이력은 LLM에 가지 않는다).</li>
 *     <li>검색이 잠시 실패해 인기순이면({@code POPULAR_TEMPORARY}) 결과에 안내 한 줄을 넣어 LLM이 관심사를 반영하지 못했다고 말하게 한다.</li>
 *     <li>사용 지표는 {@code hapbang.chatbot.tool.calls{tool=recommendInfluencers}}로 센다(대화 진입점의 사용 수와 따로).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class InfluencerRecommendTools {

    static final String TOOL_NAME = "recommendInfluencers";

    private final InfluencerRecommendUseCase useCase;
    private final InfluencerRecommendMapper mapper;
    private final ChatbotInfluencerRecommendProperties properties;
    private final ChatbotMetrics metrics;

    @Tool(name = TOOL_NAME, description = """
            합방(함께 방송) 출연 상대로 어울리는 인플루언서를 추천한다. 사용자가 출연 상대·합방할 사람·비슷한 인플루언서를 찾을 때 쓴다. \
            추천 인플루언서마다 분야 이름, 그 인플루언서가 완료한 출연 수(사용자와 함께한 수가 아님), 평균 별점, 추천 이유 코드를 돌려준다. \
            화면에는 추천 카드가 따로 나가므로 답에서는 추천 이유를 짧게 설명한다. needsInterest가 true면 사용자 정보가 부족해 \
            인기순이므로 원하는 분야를 물어봐도 좋다. guide가 있으면 그 내용을 답에 반영한다.""")
    public Result recommendInfluencers(
            @ToolParam(required = false, description = """
                    사용자가 원하는 상대의 분야·분위기를 한 문장으로. 예: '롤 방송하는 사람', '요리 콘텐츠'. \
                    사용자가 말하지 않았으면 비운다.""")
            @Nullable String interest,
            ToolContext toolContext) {
        long memberId = ChatToolContext.memberId(toolContext, TOOL_NAME);
        ChatCardCollector cards = ChatToolContext.cards(toolContext, TOOL_NAME);
        metrics.toolCall(TOOL_NAME);
        cards.called(TOOL_NAME);
        InfluencerRecommendResDto response = mapper.response(useCase.recommend(memberId, normalize(interest)));
        cards.influencers(new ChatCardsResDto.InfluencerCards(response.basis(), response.items()));
        List<Item> items = response.items().stream().map(Result::item).toList();
        return new Result(items, response.needsInterest(), response.basis(), RecommendGuide.of(response.basis()),
                response.notice());
    }

    private @Nullable String normalize(@Nullable String interest) {
        if (interest == null || interest.isBlank()) {
            return null;
        }
        return TextCut.head(interest.strip(), properties.interestMaxLength());
    }

    /**
     * 대화 LLM에 돌려주는 결과. 회원 ID와 함께 출연한 이력은 없다(카드에만 있다).
     *
     * @param guide  LLM이 답에 반영할 안내 한 줄(검색 일시 장애). 없으면 null
     * @param notice 추천하지 못한 까닭(요청 회원 숨김). 없으면 null
     */
    public record Result(List<Item> items, boolean needsInterest, RecommendBasis basis, @Nullable String guide,
            @Nullable String notice) {

        static Item item(InfluencerItemResDto card) {
            List<InfluencerReason> reasons = card.reasons().stream()
                    .filter(reason -> reason != InfluencerReason.PAST_PARTNER).toList();
            return new Item(card.categoryNames(), card.completedAppearances(), card.averageRating(), reasons);
        }
    }

    /** 추천 인플루언서 하나(공개 정보만) */
    public record Item(List<String> categoryNames, long completedAppearances, @Nullable Double averageRating,
            List<InfluencerReason> reasons) {
    }
}
