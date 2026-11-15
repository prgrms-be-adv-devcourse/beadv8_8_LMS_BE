package com.hapbang.auxiliary.chatbot.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.FallbackReason;
import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 추천에 LLM 이유 문장을 붙인다 (#718, 챗봇 "강의 추천받기" 버튼 경로). 버튼 화면 연결은 #720이 한다.
 * <ul>
 *     <li><b>후보는 {@link LectureRecommendUseCase}가 정한다.</b> LLM은 고른 강의마다 이유 문장만 쓴다.</li>
 *     <li>{@code .entity()}로 {@code [{lectureId, reason}]}를 받는다. 결과 목록은 후보 목록 그대로이고 문장만 바꾸므로, LLM이 말한
 *     후보 밖 강의는 들어올 자리가 없다. 빈 문장은 버리고 긴 문장은 자른다(카드 크기).</li>
 *     <li>LLM이 실패하거나({@link LlmCallGuard}의 회로·상한·시간 초과 포함) 빠뜨린 강의는 이유 코드의 정해진 문장 그대로다.
 *     AI가 죽어도 후보 목록은 나간다.</li>
 *     <li>프롬프트에는 강의 제목·소개(공개 정보)와 이유 코드 설명만 넣는다. 회원 정보·회원이 산 강의는 넣지 않는다.</li>
 *     <li>대화 Tool({@link LectureRecommendTools})은 이것을 부르지 않는다. 바깥 대화 LLM이 Tool 결과로 답을 쓰므로 중복이다.</li>
 *     <li>회원별 호출 제한은 여기서 세지 않는다. 버튼을 연결하는 대화 진입점(#720)의 제한이 이 호출(관심사 임베딩·LLM)을 감싼다.</li>
 * </ul>
 */
@Slf4j
@Component
public class LectureRecommendExplainUseCase {

    static final String SYSTEM_PROMPT = """
            너는 합방 서비스의 강의 추천 카드에 들어갈 추천 이유를 쓴다. 항상 한국어로 쓴다.
            규칙:
            - 주어진 강의마다 하나씩, 그 강의의 제목·소개만 근거로 왜 들어 볼 만한지 한두 문장으로 쓴다.
            - 추천 근거 코드는 왜 후보가 되었는지다. 문장의 방향을 잡는 데만 쓰고 코드 이름은 쓰지 않는다.
            - 주어진 강의 ID만 쓴다. 강의를 더하거나 빼지 않는다.
            - 가격·할인·수강생 수처럼 주어지지 않은 사실은 지어내지 않는다.
            - 문장마다 %d자를 넘기지 않는다.
            """;

    private static final ParameterizedTypeReference<List<LlmReason>> REASONS = new ParameterizedTypeReference<>() {
    };

    private final @Nullable ChatClient chatClient;
    private final LectureRecommendUseCase recommendUseCase;
    private final LectureRecommendStore store;
    private final ChatbotLectureRecommendProperties properties;
    private final ChatbotMetrics metrics;
    private final LlmCallGuard llm;

    LectureRecommendExplainUseCase(ObjectProvider<ChatModel> chatModel, ObjectProvider<ChatClient.Builder> chatClientBuilder,
            LectureRecommendUseCase recommendUseCase, LectureRecommendStore store, ChatbotLectureRecommendProperties properties,
            ChatbotMetrics metrics, LlmCallGuard llm) {
        // ChatClient.Builder 빈 정의는 모델이 없어도 있고, 그때 꺼내면 예외다. 모델이 있을 때만 꺼낸다(FaqChatService와 같다)
        this.chatClient = chatModel.getIfAvailable() == null ? null
                : chatClientBuilder.getObject().defaultSystem(SYSTEM_PROMPT.formatted(properties.reasonMaxLength())).build();
        this.recommendUseCase = recommendUseCase;
        this.store = store;
        this.properties = properties;
        this.metrics = metrics;
        this.llm = llm;
    }

    /** 후보를 만들고 LLM 이유 문장을 붙인다 */
    public LectureRecommendation recommend(long memberId, @Nullable String interest) {
        LectureRecommendation recommendation = recommendUseCase.recommend(memberId, interest);
        if (recommendation.items().isEmpty()) {
            return recommendation;
        }
        String prompt = userPrompt(recommendation);
        LlmCallGuard.Result<List<LlmReason>> result = llm.call(chatClient != null, properties.explainTimeout(),
                () -> chatClient.prompt().user(prompt).call().entity(REASONS));
        if (!result.succeeded()) {
            if (result.reason() == FallbackReason.ERROR || result.reason() == FallbackReason.TIMEOUT) {
                log.warn("강의 추천 이유 문장을 만들지 못해 정해진 문장으로 답한다 reason={}", result.reason(), result.error());
            }
            metrics.fallback(Feature.LECTURE_RECOMMEND, result.reason());
            return recommendation;
        }
        Map<Long, String> reasons = byLectureId(result.value());
        // 후보 강의(코드가 정한 목록)마다 문장을 찾아 바꾼다. LLM이 말한 후보 밖 강의는 찾을 일이 없어 버려진다
        return recommendation.withReasonTexts(item -> reasons.getOrDefault(item.lectureId(), item.reasonText()));
    }

    // 강의 ID → 문장. 빈 문장은 버리고, 긴 문장은 자른다
    private Map<Long, String> byLectureId(@Nullable List<LlmReason> reasons) {
        Map<Long, String> byId = new HashMap<>();
        if (reasons == null) {
            return byId;
        }
        for (LlmReason reason : reasons) {
            if (reason != null && reason.lectureId() != null && reason.reason() != null && !reason.reason().isBlank()) {
                byId.put(reason.lectureId(), TextCut.head(reason.reason().strip(), properties.reasonMaxLength()));
            }
        }
        return byId;
    }

    private String userPrompt(LectureRecommendation recommendation) {
        List<Long> ids = recommendation.items().stream().map(LectureRecommendation.Item::lectureId).toList();
        Map<Long, LectureSnapshot> lectures = store.lectures(ids);
        int descriptionMax = properties.explainDescriptionMaxLength();
        StringBuilder prompt = new StringBuilder("아래 강의마다 추천 이유를 써라.\n");
        for (LectureRecommendation.Item item : recommendation.items()) {
            LectureSnapshot lecture = lectures.get(item.lectureId());
            String description = lecture == null || lecture.description() == null ? ""
                    : TextCut.head(lecture.description(), descriptionMax);
            prompt.append("\n- 강의 ID: ").append(item.lectureId())
                    .append("\n  제목: ").append(item.title())
                    .append("\n  소개: ").append(description)
                    .append("\n  추천 근거 코드: ").append(item.reason().description());
        }
        return prompt.toString();
    }

    /** LLM 응답 한 항목 */
    record LlmReason(@Nullable Long lectureId, @Nullable String reason) {
    }
}
