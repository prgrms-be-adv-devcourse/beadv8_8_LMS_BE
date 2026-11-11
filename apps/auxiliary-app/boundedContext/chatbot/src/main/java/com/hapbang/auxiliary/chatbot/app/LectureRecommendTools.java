package com.hapbang.auxiliary.chatbot.app;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;

import lombok.RequiredArgsConstructor;

/**
 * 모델이 부르는 강의 추천 Tool (#718). 자유 질문 대화(#720)가 Tool 목록에 넣는다.
 * <ul>
 *     <li><b>회원 ID는 요청마다 넘긴 {@link ToolContext}({@link #MEMBER_ID})에서만 꺼낸다.</b> Tool 인자로 받지 않는다
 *     ({@code ToolContext} 인자는 모델에게 보이는 인자 스키마에서 빠진다). 없으면 호출 쪽 잘못이라 거절한다.</li>
 *     <li>후보는 코드가 정한다({@link LectureRecommendUseCase}). 이유는 정해진 문장이다. 답 문장은 바깥 대화 LLM이 이 결과로 쓴다.
 *     화면의 추천 카드는 LLM 글이 아니라 이 결과로 그린다(#720).</li>
 *     <li>일반 API는 이 객체를 쓰지 않는다(Tool 서비스와 일반 API 분리, {@link FaqTools}와 같은 규칙).</li>
 *     <li>회원별 호출 제한은 여기서 세지 않는다. Tool을 부르는 대화 진입점(#720)의 제한이 대화 한 번(관심사 임베딩 포함)을 감싼다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LectureRecommendTools {

    /** ToolContext의 요청 회원 ID 키. 값은 {@link Long} */
    public static final String MEMBER_ID = "memberId";

    private final LectureRecommendUseCase recommendUseCase;

    @Tool(name = "recommendLectures", description = """
            로그인한 회원에게 맞춤 강의를 추천한다. 회원의 구매·장바구니·리뷰 활동으로 고르고, 관심사를 주면 함께 반영한다. \
            결과의 items가 추천 강의(강의 ID, 제목, 추천 이유)다. needsInterest가 true면 지금은 맞춤으로 고를 수 없어 인기 강의를 \
            준 것이고 관심사를 알려 주면 맞춤으로 찾을 수 있으니, 어떤 분야에 관심 있는지 물어본다. \
            personalized가 false면 맞춤이 아닌 인기 강의다.""")
    public Result recommendLectures(
            @ToolParam(required = false, description = "사용자가 말한 관심 분야나 배우고 싶은 것을 한 문장으로. 예: '데이터 분석 입문', "
                    + "'유튜브 영상 편집'. 사용자가 말하지 않았으면 비운다") @Nullable String interest,
            ToolContext toolContext) {
        long memberId = memberId(toolContext);
        LectureRecommendation recommendation = recommendUseCase.recommend(memberId, normalize(interest));
        List<Item> items = recommendation.items().stream()
                .map(item -> new Item(item.lectureId(), item.title(), item.reason(), item.reasonText()))
                .toList();
        return new Result(items, recommendation.needsInterest(), recommendation.personalized());
    }

    private static long memberId(@Nullable ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext().get(MEMBER_ID) instanceof Long memberId) {
            return memberId;
        }
        throw new IllegalStateException("ToolContext에 " + MEMBER_ID + "가 없어 강의 추천을 거절한다");
    }

    private static @Nullable String normalize(@Nullable String interest) {
        if (interest == null || interest.isBlank()) {
            return null;
        }
        String stripped = interest.strip();
        int max = LectureRecommendUseCase.INTEREST_MAX_LENGTH;
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    /** Tool 결과 */
    public record Result(List<Item> items, boolean needsInterest, boolean personalized) {
    }

    /** 추천 강의 하나 */
    public record Item(long lectureId, String title, LectureRecommendReason reason, String reasonText) {
    }
}
