package com.hapbang.auxiliary.chatbot.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.FallbackReason;
import com.hapbang.auxiliary.chatbot.app.InfluencerRecommendUseCase.Candidate;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;

import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 추천 이유를 LLM으로 쓴다 (#719, 버튼 경로 {@code /explained}). 후보와 순서는 바꾸지 않는다.
 * <ul>
 *     <li>프롬프트에는 후보의 공개 수치만 넣는다: 분야 이름, 함께 마친 출연 수, 평균 별점, 이유 코드.
 *     <b>닉네임·자기소개 원문과 요청 회원 정보는 넣지 않는다.</b> 회원 ID는 답을 후보에 맞추는 열쇠로만 넣는다.</li>
 *     <li>답은 {@code .entity()}로 받는다. 후보 밖 ID, 빈 문장, <b>후보 회원 ID 숫자가 들어간 문장</b>은 버리고 이유 코드 문장으로 채운다.</li>
 *     <li>LLM을 쓸 수 없거나 실패하거나 쓸 만한 답이 하나도 없으면 모든 후보를 이유 코드 문장으로 답하고 fallback으로 센다
 *     (후보 목록은 그대로 나간다). 호출은 챗봇 공통 {@link LlmCallGuard}를 거친다.</li>
 * </ul>
 */
@Slf4j
@Component
public class InfluencerRecommendExplainer {

    static final String SYSTEM_PROMPT = """
            너는 합방 서비스의 출연 상대 추천 이유를 쓰는 도우미다.
            1. 주어진 후보마다 추천 이유를 한국어 한두 문장으로 쓴다. 후보 목록에 있는 사실(분야, 함께 마친 출연 수, 평균 별점, 이유)만 쓴다.
            2. 회원 ID·숫자 ID를 문장에 쓰지 않는다. 이름을 지어내지 않는다.
            3. 후보 목록에 없는 회원은 답에 넣지 않는다.
            4. 답은 후보마다 memberId와 reason을 가진 JSON 배열이다.
            """;

    private static final ParameterizedTypeReference<List<Explanation>> EXPLANATIONS = new ParameterizedTypeReference<>() {
    };

    private final @Nullable ChatClient chatClient;
    private final LlmCallGuard llm;
    private final ContentCategoryNames categoryNames;
    private final ChatbotInfluencerRecommendProperties properties;

    InfluencerRecommendExplainer(ObjectProvider<ChatModel> chatModel, ObjectProvider<ChatClient.Builder> chatClientBuilder,
            LlmCallGuard llm, ContentCategoryNames categoryNames, ChatbotInfluencerRecommendProperties properties) {
        // ChatClient.Builder 빈 정의는 모델이 없어도 있고, 그때 꺼내면 예외다. 모델이 있을 때만 꺼낸다
        this.chatClient = chatModel.getIfAvailable() == null ? null
                : chatClientBuilder.getObject().defaultSystem(SYSTEM_PROMPT).build();
        this.llm = llm;
        this.categoryNames = categoryNames;
        this.properties = properties;
    }

    /** @return 후보마다 이유 문장. LLM 답을 쓰지 못했으면 {@link Explained#fallback()}에 이유가 있다 */
    public Explained explain(List<Candidate> candidates) {
        Map<Long, String> explanations = new HashMap<>();
        for (Candidate candidate : candidates) {
            explanations.put(candidate.memberId(), reasonSentence(candidate));
        }
        if (candidates.isEmpty()) {
            return new Explained(explanations, null);
        }
        String prompt = prompt(candidates);
        LlmCallGuard.Result<List<Explanation>> result = llm.call(chatClient != null, properties.explainTimeout(),
                () -> chatClient.prompt().user(prompt).call().entity(EXPLANATIONS));
        if (!result.succeeded()) {
            if (result.reason() == FallbackReason.ERROR || result.reason() == FallbackReason.TIMEOUT) {
                log.warn("인플루언서 추천 설명을 받지 못해 이유 코드 문장으로 답한다 reason={}", result.reason(), result.error());
            }
            return new Explained(explanations, result.reason());
        }
        int used = 0;
        List<Explanation> answered = result.value() == null ? List.of() : result.value();
        for (Explanation explanation : answered) {
            if (usable(explanation, explanations.keySet())) {
                explanations.put(explanation.memberId(), explanation.reason().strip());
                used++;
            }
        }
        if (used == 0) {
            log.warn("인플루언서 추천 설명에 쓸 만한 답이 없어 이유 코드 문장으로 답한다 answered={}", answered.size());
            return new Explained(explanations, FallbackReason.ERROR);
        }
        return new Explained(explanations, null);
    }

    /** 이유 코드를 정해진 문장으로(LLM 실패·빠진 후보) */
    static String reasonSentence(Candidate candidate) {
        return descriptions(candidate, " ");
    }

    // 후보 안의 회원이고, 문장이 있고, 후보 회원 ID 숫자를 말하지 않는 답만 쓴다
    private static boolean usable(@Nullable Explanation explanation, Set<Long> candidateIds) {
        if (explanation == null || !candidateIds.contains(explanation.memberId()) || explanation.reason() == null
                || explanation.reason().isBlank()) {
            return false;
        }
        for (Long id : candidateIds) {
            if (Pattern.compile("(?<![0-9])" + id + "(?![0-9])").matcher(explanation.reason()).find()) {
                return false;
            }
        }
        return true;
    }

    // 후보의 공개 수치만. 닉네임·자기소개·요청 회원 정보는 넣지 않는다
    private String prompt(List<Candidate> candidates) {
        StringBuilder prompt = new StringBuilder("추천 후보:\n");
        for (Candidate candidate : candidates) {
            List<String> names = categoryNames.namesOf(candidate.categories());
            Double rating = candidate.averageRating();
            prompt.append("- memberId=%d | 분야: %s | 함께 마친 출연: %d회 | 평균 별점: %s | 이유: %s%n".formatted(
                    candidate.memberId(), names.isEmpty() ? "없음" : String.join(", ", names),
                    candidate.completedAppearances(), rating == null ? "리뷰 없음" : rating, descriptions(candidate, ", ")));
        }
        return prompt.toString();
    }

    private static String descriptions(Candidate candidate, String delimiter) {
        StringJoiner joined = new StringJoiner(delimiter);
        for (InfluencerReason reason : candidate.orderedReasons()) {
            joined.add(reason.getDescription());
        }
        return joined.toString();
    }

    /** LLM이 돌려주는 후보 하나의 이유 */
    record Explanation(long memberId, @Nullable String reason) {
    }

    /**
     * 설명 결과.
     *
     * @param explanations 후보 회원 ID → 이유 문장(모든 후보)
     * @param fallback     LLM 답을 쓰지 못하고 이유 코드 문장으로 답한 이유. LLM 답을 썼으면 null
     */
    public record Explained(Map<Long, String> explanations, @Nullable FallbackReason fallback) {
    }
}
