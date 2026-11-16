package com.hapbang.auxiliary.chatbot.app.dto;

import com.hapbang.auxiliary.chatbot.domain.FaqCategory;

/** FAQ 모아보기 항목 */
public record FaqResDto(String code, FaqCategory category, String question, String answer) {
}
