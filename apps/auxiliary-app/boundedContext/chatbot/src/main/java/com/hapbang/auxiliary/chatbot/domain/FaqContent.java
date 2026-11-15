package com.hapbang.auxiliary.chatbot.domain;

/** FAQ 하나의 내용(#715). 시드 한 항목에서 code를 뺀 값과 시드 파일 안의 순서다 */
public record FaqContent(FaqCategory category, String question, String answer, String policyRef, int sortOrder) {
}
