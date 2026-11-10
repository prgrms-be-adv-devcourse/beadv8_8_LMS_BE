package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.dto.InfluencerItemResDto;

import lombok.RequiredArgsConstructor;

/**
 * 모델이 부르는 인플루언서 추천 Tool (#719, 자유 질문 #720이 등록한다).
 * <ul>
 *     <li><b>회원 ID는 {@link ToolContext}의 {@value #USER_ID}에서만 꺼낸다.</b> Tool 인자로 받지 않는다(모델이 다른 회원 ID를 넣을 수 없다).
 *     ToolContext 인자는 Tool 스키마에서 빠진다. 없으면 호출 쪽 잘못이라 거절한다.</li>
 *     <li>관심사 문장은 대화에서 모델이 채운다(예: "롤 방송"). 있으면 그 문장으로 찾고, 없으면 버튼 경로와 같다.</li>
 *     <li>돌려주는 값에는 닉네임·자기소개가 없다. 대화 LLM은 이 값으로 설명하고, 카드는 회원 ID로 FE가 그린다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class InfluencerRecommendTools {

    /** ToolContext에 요청 회원 ID를 담는 키(값은 Long) */
    public static final String USER_ID = "userId";

    /** 관심사 문장의 최대 길이(API 입력 상한과 같다). 넘으면 자른다 */
    static final int INTEREST_MAX_LENGTH = 100;

    private final InfluencerRecommendUseCase useCase;
    private final InfluencerRecommendMapper mapper;

    @Tool(name = "recommendInfluencers", description = """
            합방(함께 방송) 출연 상대로 어울리는 인플루언서를 추천한다. 사용자가 출연 상대·합방할 사람·비슷한 인플루언서를 찾을 때 쓴다. \
            회원 ID, 분야 이름, 함께 마친 출연 수, 평균 별점, 추천 이유 코드를 돌려준다. 회원 ID는 답에 쓰지 않는다.""")
    public List<InfluencerItemResDto> recommendInfluencers(
            @ToolParam(required = false, description = """
                    사용자가 원하는 상대의 분야·분위기를 한 문장으로. 예: '롤 방송하는 사람', '요리 콘텐츠'. \
                    사용자가 말하지 않았으면 비운다.""")
            @Nullable String interest,
            ToolContext toolContext) {
        long userId = userId(toolContext);
        InfluencerRecommendUseCase.InfluencerRecommendation recommendation = useCase.recommend(userId, normalize(interest));
        return mapper.items(recommendation.items(), null);
    }

    private static long userId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext().get(USER_ID) instanceof Long userId) {
            return userId;
        }
        throw new IllegalStateException("ToolContext에 " + USER_ID + "가 없다");
    }

    private static @Nullable String normalize(@Nullable String interest) {
        if (interest == null || interest.isBlank()) {
            return null;
        }
        String stripped = interest.strip();
        return stripped.length() > INTEREST_MAX_LENGTH ? stripped.substring(0, INTEREST_MAX_LENGTH) : stripped;
    }
}
