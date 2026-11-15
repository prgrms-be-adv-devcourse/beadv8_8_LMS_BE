package com.hapbang.auxiliary.chatbot.app;

import com.hapbang.auxiliary.chatbot.domain.FaqCategory;
import com.hapbang.auxiliary.chatbot.domain.FaqContent;

/** 시드 파일(chatbot/faq-seed.json) 한 항목. 순서는 파일 안 위치다 */
public record FaqSeed(String code, FaqCategory category, String question, String answer, String policyRef) {

    /** 파일 안 순서를 붙인 FAQ 내용 */
    FaqContent content(int sortOrder) {
        return new FaqContent(category, question, answer, policyRef, sortOrder);
    }
}
