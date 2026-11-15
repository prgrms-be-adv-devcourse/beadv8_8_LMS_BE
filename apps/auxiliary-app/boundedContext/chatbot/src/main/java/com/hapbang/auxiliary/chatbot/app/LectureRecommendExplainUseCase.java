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
import com.hapbang.auxiliary.chatbot.out.LectureRecommendStore;

import lombok.RequiredArgsConstructor;

/**
 * 챗봇 "강의 추천받기" 버튼 경로 (#718, #720). 쿼리 확장(관심사가 있을 때) → 맞춤 후보 풀 → LLM 리랭킹 + 이유 → 인기순 채우기.
 * <ul>
 *     <li><b>후보 풀은 코드가 만든다</b>({@link LectureRecommendUseCase#pool}, 맞춤 후보만, 제외 규칙·판매자당 상한 적용). LLM은 풀에서만
 *     고른다. 풀이 비면(신호도 관심사도 없음, 검색 장애) LLM을 부르지 않고 인기순이다.</li>
 *     <li>LLM이 고른 강의 → 남은 풀(코드 순서) → 인기순으로 추천 수를 채운다. LLM이 고른 강의만 LLM 문장이고 나머지는 이유 코드 문장이다.</li>
 *     <li>프롬프트에는 강의 제목·소개(공개 정보)와 이유 코드 설명만 넣는다. 회원 정보·회원이 산 강의(이유 문장의 기준 강의 제목)는 넣지 않는다.</li>
 *     <li>LLM 호출은 리랭킹 1회 + 관심사가 있을 때 쿼리 확장 1회다. 회원별 호출 제한은 진입점이 센다.</li>
 *     <li>대화 Tool({@link LectureRecommendTools})은 이것을 부르지 않는다(호출 수를 늘리지 않음, 코드 순서 그대로).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LectureRecommendExplainUseCase {

    private final LectureRecommendUseCase recommendUseCase;
    private final LectureRecommendStore store;
    private final QueryExpander queryExpander;
    private final RecommendReranker reranker;
    private final ChatbotLectureRecommendProperties properties;
    private final ChatbotRecommendLlmProperties llmProperties;

    /**
     * @param interest 관심사 원문({@link RecommendInterest#normalize}를 거친 값). 없으면 null
     */
    public Explained recommend(long memberId, @Nullable String interest) {
        List<String> queries = interest == null ? null : queryExpander.expand(interest);
        LectureRecommendUseCase.Pool pool = recommendUseCase.pool(memberId, queries, llmProperties.rerank().poolSize());
        if (pool.ranked().isEmpty()) {
            return new Explained(recommendUseCase.complete(pool, List.of()), false);
        }
        List<Long> ids = pool.lectureIds();
        RecommendReranker.Reranked reranked = reranker.rerank(Feature.LECTURE_BUTTON, "수강할 강의", candidateLines(pool),
                properties.resultSize());
        Map<Long, String> llmReasons = reranked.reasonsById(ids);
        LectureRecommendation recommendation = recommendUseCase.complete(pool, reranked.chosenThenRest(ids))
                .withReasonTexts(item -> llmReasons.getOrDefault(item.lectureId(), item.reasonText()));
        return new Explained(recommendation, reranked.fallback() != null);
    }

    // 강의마다 공개 정보 한 줄: 제목·소개(잘라서)·이유 코드 설명. 기준 강의 제목(회원이 산 강의)은 넣지 않는다
    private List<String> candidateLines(LectureRecommendUseCase.Pool pool) {
        Map<Long, LectureSnapshot> lectures = store.lectures(pool.lectureIds());
        int descriptionMax = llmProperties.rerank().descriptionMaxLength();
        List<String> lines = new ArrayList<>();
        for (LectureRanker.Ranked ranked : pool.ranked()) {
            LectureSnapshot lecture = lectures.get(ranked.lectureId());
            String title = lecture == null ? "" : lecture.title();
            String description = lecture == null || lecture.description() == null ? ""
                    : TextCut.head(lecture.description(), descriptionMax).replace('\n', ' ');
            lines.add("제목: " + title + " | 소개: " + description + " | 추천 근거: "
                    + pool.reasons().get(ranked.lectureId()).reason().description());
        }
        return lines;
    }

    /**
     * @param fallback LLM 답을 쓰지 못하고 이유 코드 문장·코드 순서로 답했다(실패·시간 초과·형식 오류·쓸 만한 답 없음)
     */
    public record Explained(LectureRecommendation recommendation, boolean fallback) {
    }
}
