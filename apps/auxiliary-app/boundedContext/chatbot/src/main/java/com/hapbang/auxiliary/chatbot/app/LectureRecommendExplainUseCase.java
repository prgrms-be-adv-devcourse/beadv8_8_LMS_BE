package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotMetrics.Feature;
import com.hapbang.auxiliary.chatbot.config.ChatbotLectureRecommendProperties;
import com.hapbang.auxiliary.chatbot.config.ChatbotRecommendLlmProperties;
import com.hapbang.auxiliary.chatbot.domain.LectureRanker;
import com.hapbang.auxiliary.chatbot.domain.LectureSnapshot;
import com.hapbang.auxiliary.chatbot.domain.TextCut;

/**
 * 챗봇 "강의 추천받기" 버튼 경로 (#718, #720). 쿼리 확장(관심사가 있을 때) → 맞춤 후보 풀 → LLM 리랭킹 + 이유 → 인기순 채우기
 * (인플루언서 버튼과 같은 흐름 {@link RecommendExplainFlow}).
 * <ul>
 *     <li><b>후보 풀은 코드가 만든다</b>({@link LectureRecommendUseCase#pool}, 맞춤 후보만, 제외 규칙·판매자당 상한 적용). LLM은 풀에서만
 *     고른다. 풀이 비면(신호도 관심사도 없음, 검색 장애) LLM을 부르지 않고 인기순이다.</li>
 *     <li>LLM이 고른 강의 → 남은 풀(코드 순서) → 인기순으로 추천 수를 채운다. LLM이 고른 강의만 LLM 문장이고 나머지는 이유 코드 문장이다.</li>
 *     <li>프롬프트에는 강의 제목·소개(공개 정보)와 이유 코드 설명을 넣는다. 회원이 산 강의(이유 문장의 기준 강의 제목)는 넣지 않는다.
 *     요청 회원의 취향은 {@code rerank.taste=PUBLIC}일 때만 관심사와 공개 프로필을 넣는다({@link RecommendTasteSupport}, #948).</li>
 *     <li>LLM 호출은 리랭킹 1회 + 관심사가 있을 때 쿼리 확장 1회다. 회원별 호출 제한은 진입점이 센다.</li>
 *     <li>대화 Tool({@link LectureRecommendTools})은 이것을 부르지 않는다(호출 수를 늘리지 않음, 코드 순서 그대로).</li>
 * </ul>
 */
@Component
public class LectureRecommendExplainUseCase {

    private final LectureRecommendUseCase recommendUseCase;
    private final RecommendExplainFlow flow;
    private final ChatbotLectureRecommendProperties properties;
    private final ChatbotRecommendLlmProperties llmProperties;

    LectureRecommendExplainUseCase(LectureRecommendUseCase recommendUseCase, QueryExpander queryExpander,
            RecommendReranker reranker, RecommendTasteSupport tasteSupport, ChatbotLectureRecommendProperties properties,
            ChatbotRecommendLlmProperties llmProperties) {
        this.recommendUseCase = recommendUseCase;
        this.flow = new RecommendExplainFlow(queryExpander, reranker, tasteSupport, llmProperties);
        this.properties = properties;
        this.llmProperties = llmProperties;
    }

    /**
     * @param interest 관심사 원문({@link RecommendInterest#normalize}를 거친 값). 없으면 null
     */
    public Explained recommend(long memberId, @Nullable String interest) {
        RecommendExplainFlow.Steps<LectureRecommendUseCase.Pool, LectureRecommendation> steps = new RecommendExplainFlow.Steps<>(
                (queries, poolSize) -> recommendUseCase.pool(memberId, queries, poolSize),
                LectureRecommendUseCase.Pool::lectureIds,
                this::candidateLines,
                recommendUseCase::complete);
        RecommendExplainFlow.Explained<LectureRecommendation> explained = flow.recommend(Feature.LECTURE_BUTTON, "수강할 강의",
                memberId, interest, properties.resultSize(), steps);
        Map<Long, String> llmReasons = explained.llmReasons();
        LectureRecommendation recommendation = explained.recommendation()
                .withReasonTexts(item -> llmReasons.getOrDefault(item.lectureId(), item.reasonText()));
        return new Explained(recommendation, explained.fallback());
    }

    // 강의마다 공개 정보 한 줄: 제목·소개(잘라서)·이유 코드 설명. 기준 강의 제목(회원이 산 강의)은 넣지 않는다.
    // 강의는 풀을 만들 때 읽은 사본을 쓴다(다시 읽지 않는다)
    private List<String> candidateLines(LectureRecommendUseCase.Pool pool) {
        Map<Long, LectureSnapshot> lectures = pool.lectures();
        int descriptionMax = llmProperties.rerank().descriptionMaxLength();
        List<String> lines = new ArrayList<>();
        for (LectureRanker.Ranked ranked : pool.ranked()) {
            LectureSnapshot lecture = lectures.get(ranked.lectureId());
            String title = lecture == null ? "" : lecture.title();
            String description = lecture == null || lecture.description() == null ? ""
                    : TextCut.head(lecture.description(), descriptionMax).replace('\n', ' ');
            lines.add("제목: " + title + " | 소개: " + description + " | 추천 근거: "
                    + pool.reasons().get(ranked.lectureId()).reason().getDescription());
        }
        return lines;
    }

    /**
     * @param fallback LLM 답을 쓰지 못하고 이유 코드 문장·코드 순서로 답했다(실패·시간 초과·형식 오류·쓸 만한 답 없음)
     */
    public record Explained(LectureRecommendation recommendation, boolean fallback) {
    }
}
