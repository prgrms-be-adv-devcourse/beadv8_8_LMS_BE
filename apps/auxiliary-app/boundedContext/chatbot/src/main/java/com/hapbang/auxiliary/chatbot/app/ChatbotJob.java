package com.hapbang.auxiliary.chatbot.app;

import com.hapbang.auxiliary.global.scheduling.LockedJob;

/**
 * 챗봇에서 한 pod만 실행해야 하는 작업. 번호 규칙은 {@link LockedJob}(이슈 번호 × 1000 + 순번).
 */
public enum ChatbotJob implements LockedJob {

    /** FAQ 시드 맞추기와 색인 (#715) */
    FAQ_SYNC(715_001L, "chatbot-faq-sync");

    private final long lockKey;
    private final String tag;

    ChatbotJob(long lockKey, String tag) {
        this.lockKey = lockKey;
        this.tag = tag;
    }

    @Override
    public long lockKey() {
        return lockKey;
    }

    @Override
    public String tag() {
        return tag;
    }
}
