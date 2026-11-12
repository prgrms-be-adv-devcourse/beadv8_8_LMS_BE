package com.hapbang.auxiliary.chatbot.app;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.dto.InfluencerRecommendResDto;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;

import lombok.RequiredArgsConstructor;

/**
 * 모델이 부르는 인플루언서 추천 Tool (#719, 자유 질문 #720이 등록한다).
 * <ul>
 *     <li><b>회원 ID는 {@link ToolContext}의 {@link ChatToolContext#MEMBER_ID}에서만 꺼낸다.</b> Tool 인자로 받지 않는다(모델이 다른 회원 ID를 넣을 수 없다).
 *     ToolContext 인자는 Tool 스키마에서 빠진다. 없으면 호출 쪽 잘못이라 거절한다.</li>
 *     <li>관심사 문장은 대화에서 모델이 채운다(예: "롤 방송"). 있으면 그 문장으로 찾고, 없으면 버튼 경로와 같다.
 *     {@code interest-max-length}를 넘으면 자른다(임베딩 비용 상한). 대화 전체의 호출 제한은 자유 질문(#720)이 건다.</li>
 *     <li>{@code needsInterest}가 true면 개인 신호가 없어 인기순이다. 대화 LLM이 원하는 분야를 되물을 수 있다.</li>
 *     <li>사용 지표는 {@code hapbang.chatbot.tool.calls{tool=recommendInfluencers}}로 센다(대화 진입점의 사용 수와 따로).</li>
 *     <li>돌려주는 값에는 닉네임·자기소개가 없다. 대화 LLM은 이 값으로 설명하고, 카드는 회원 ID로 FE가 그린다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class InfluencerRecommendTools {

    private final InfluencerRecommendUseCase useCase;
    private final InfluencerRecommendMapper mapper;
    private final ChatbotInfluencerRecommendProperties properties;
    private final ChatbotMetrics metrics;

    static final String TOOL_NAME = "recommendInfluencers";

    @Tool(name = TOOL_NAME, description = """
            합방(함께 방송) 출연 상대로 어울리는 인플루언서를 추천한다. 사용자가 출연 상대·합방할 사람·비슷한 인플루언서를 찾을 때 쓴다. \
            회원 ID, 분야 이름, 그 인플루언서가 완료한 출연 수(사용자와 함께한 수가 아님), 평균 별점, 추천 이유 코드를 돌려준다. \
            회원 ID는 답에 쓰지 않는다. needsInterest가 true면 사용자 정보가 부족해 인기순이므로 원하는 분야를 물어봐도 좋다.""")
    public InfluencerRecommendResDto recommendInfluencers(
            @ToolParam(required = false, description = """
                    사용자가 원하는 상대의 분야·분위기를 한 문장으로. 예: '롤 방송하는 사람', '요리 콘텐츠'. \
                    사용자가 말하지 않았으면 비운다.""")
            @Nullable String interest,
            ToolContext toolContext) {
        long memberId = ChatToolContext.memberId(toolContext, TOOL_NAME);
        metrics.toolCall(TOOL_NAME);
        return mapper.response(useCase.recommend(memberId, normalize(interest)));
    }

    private @Nullable String normalize(@Nullable String interest) {
        if (interest == null || interest.isBlank()) {
            return null;
        }
        return TextCut.head(interest.strip(), properties.interestMaxLength());
    }
}
