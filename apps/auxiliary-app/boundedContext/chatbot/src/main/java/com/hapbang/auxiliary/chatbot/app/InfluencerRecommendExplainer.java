package com.hapbang.auxiliary.chatbot.app;

import java.util.HashMap;
import java.util.HashSet;
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
 *     <li>프롬프트에는 후보의 공개 수치만 넣는다: 분야 이름, 후보가 완료한 출연 수(요청 회원과 함께한 수가 아님), 평균 별점, 이유 코드.
 *     <b>닉네임·자기소개 원문, 요청 회원 정보, 회원 ID는 넣지 않는다.</b> 후보는 순번(1부터)으로 부르고 답의 순번을 코드가 회원 ID로 되돌린다.
 *     모델이 회원 ID를 모르므로 문장에 쓸 수도 없다.</li>
 *     <li>답은 {@code .entity()}로 받는다. 범위 밖 순번, 빈 문장, 순번을 말한 문장("후보 2")은 버리고 이유 코드 문장으로 채운다.</li>
 *     <li>LLM을 쓸 수 없거나 실패하거나 쓸 만한 답이 하나도 없으면 모든 후보를 이유 코드 문장으로 답하고 fallback으로 센다
 *     (후보 목록은 그대로 나간다). 호출은 챗봇 공통 {@link LlmCallGuard}를 거친다.</li>
 * </ul>
 */
@Slf4j
@Component
public class InfluencerRecommendExplainer {

    static final String SYSTEM_PROMPT = """
            너는 합방 서비스의 출연 상대 추천 이유를 쓰는 도우미다.
            1. 주어진 후보마다 추천 이유를 한국어 한두 문장으로 쓴다. 후보 목록에 있는 사실(분야, 완료한 출연 수, 평균 별점, 이유)만 쓴다. 완료한 출연 수는 그 후보가 지금까지 마친 출연 전체이고 사용자와 함께한 수가 아니다.
            2. 후보 번호를 문장에 쓰지 않는다. 이름을 지어내지 않는다.
            3. 후보 목록에 없는 번호는 답에 넣지 않는다.
            4. 답은 후보마다 candidate(후보 번호)와 reason을 가진 JSON 배열이다.
            """;

    // 순번을 말한 문장("후보 2", "후보2번")
    private static final Pattern CANDIDATE_MENTION = Pattern.compile("후보\\s*\\d");

    private static final ParameterizedTypeReference<List<Explanation>> EXPLANATIONS = new ParameterizedTypeReference<>() {
    };

    private final @Nullable ChatClient chatClient;
    private final LlmCallGuard llm;
    private final ChatbotCategoryNames categoryNames;
    private final ChatbotInfluencerRecommendProperties properties;

    InfluencerRecommendExplainer(ObjectProvider<ChatModel> chatModel, ObjectProvider<ChatClient.Builder> chatClientBuilder,
            LlmCallGuard llm, ChatbotCategoryNames categoryNames, ChatbotInfluencerRecommendProperties properties) {
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
            return new Explained.Done(explanations);
        }
        String prompt = prompt(candidates);
        LlmCallGuard.Result<List<Explanation>> result = llm.call(chatClient != null, properties.explainTimeout(),
                () -> chatClient.prompt().user(prompt).call().entity(EXPLANATIONS));
        if (!result.succeeded()) {
            if (result.reason() == FallbackReason.ERROR || result.reason() == FallbackReason.TIMEOUT) {
                log.warn("인플루언서 추천 설명을 받지 못해 이유 코드 문장으로 답한다 reason={}", result.reason(), result.error());
            }
            return new Explained.Fallback(explanations, result.reason());
        }
        List<Explanation> answered = result.value() == null ? List.of() : result.value();
        // 같은 순번이 여러 번 오면 처음 답만 쓴다
        Set<Integer> answeredCandidates = new HashSet<>();
        for (Explanation explanation : answered) {
            if (usable(explanation, candidates.size()) && answeredCandidates.add(explanation.candidate())) {
                explanations.put(candidates.get(explanation.candidate() - 1).memberId(), explanation.reason().strip());
            }
        }
        int used = answeredCandidates.size();
        if (used == 0) {
            log.warn("인플루언서 추천 설명에 쓸 만한 답이 없어 이유 코드 문장으로 답한다 answered={}", answered.size());
            return new Explained.Fallback(explanations, FallbackReason.ERROR);
        }
        return new Explained.Done(explanations);
    }

    /** 이유 코드를 정해진 문장으로(LLM 실패·빠진 후보) */
    static String reasonSentence(Candidate candidate) {
        return descriptions(candidate, " ");
    }

    // 범위 안의 순번이고, 문장이 있고, 순번을 말하지 않는 답만 쓴다
    private static boolean usable(@Nullable Explanation explanation, int candidateCount) {
        if (explanation == null || explanation.candidate() < 1 || explanation.candidate() > candidateCount
                || explanation.reason() == null || explanation.reason().isBlank()) {
            return false;
        }
        return !CANDIDATE_MENTION.matcher(explanation.reason()).find();
    }

    // 후보의 공개 수치만. 닉네임·자기소개·요청 회원 정보·회원 ID는 넣지 않는다. 후보는 순번(1부터)으로 부른다
    private String prompt(List<Candidate> candidates) {
        StringBuilder prompt = new StringBuilder("추천 후보:\n");
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            List<String> names = categoryNames.namesOf(candidate.categories());
            Double rating = candidate.averageRating();
            prompt.append("- 후보 %d | 분야: %s | 완료한 출연: %d회 | 평균 별점: %s | 이유: %s%n".formatted(
                    i + 1, names.isEmpty() ? "없음" : String.join(", ", names),
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

    /** LLM이 돌려주는 후보 하나의 이유. candidate는 프롬프트의 후보 순번(1부터) */
    record Explanation(int candidate, @Nullable String reason) {
    }

    /** 설명 결과. explanations는 후보 회원 ID → 이유 문장(모든 후보) */
    public sealed interface Explained {

        Map<Long, String> explanations();

        /** 설명했다: LLM 답을 썼다(빠진 후보는 이유 코드 문장). 후보가 없으면 LLM을 부르지 않고 이것이다 */
        record Done(Map<Long, String> explanations) implements Explained {
        }

        /** LLM 답을 쓰지 못해 모든 후보를 이유 코드 문장으로 답했다 */
        record Fallback(Map<Long, String> explanations, FallbackReason reason) implements Explained {
        }
    }
}
