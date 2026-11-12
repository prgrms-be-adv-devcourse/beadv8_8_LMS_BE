package com.hapbang.auxiliary.chatbot.app.dto;

import java.util.List;

import com.hapbang.auxiliary.chatbot.app.FaqAnswer;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 자유 질문의 답 (#720).
 *
 * @param answer   답변 글(설명). 카드는 {@code cards}로 따로 그린다
 * @param sources  근거 FAQ(Tool이 찾은 것)
 * @param cards    추천 Tool이 만든 카드(이번 요청에서 부른 종류만)
 * @param fallback LLM 대신 정해진 문장·검색 결과로 답했으면 true
 */
@Schema(description = "자유 질문 답")
public record ChatAnswerResDto(
        @Schema(description = "답변 글. 추천은 cards로 그리고 이 글은 설명으로만 쓴다") String answer,
        @Schema(description = "근거 FAQ") List<FaqAnswer.FaqSource> sources,
        @Schema(description = "추천 카드. 추천 Tool을 부르지 않았으면 빈 객체") ChatCardsResDto cards,
        @Schema(description = "LLM이 실패해 정해진 문장·FAQ 검색 결과로 답했다") boolean fallback) {
}
