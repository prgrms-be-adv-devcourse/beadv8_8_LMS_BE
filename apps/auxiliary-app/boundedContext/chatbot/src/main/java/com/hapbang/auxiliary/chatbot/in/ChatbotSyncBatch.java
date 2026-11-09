package com.hapbang.auxiliary.chatbot.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotSyncUseCase;
import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 챗봇 주간 싱크 배치 (#716). 실행 시각만 정하고 처리는 {@link ChatbotSyncUseCase}가 한다.
 * 운영 시간 안(월요일 10:00)에 돌리고, 실패·건너뜀이면 12:00에 한 번 더 돌린다. 그래도 실패면 다음 주다.
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChatbotSyncBatch {

    private final ChatbotSyncUseCase chatbotSyncUseCase;

    @Scheduled(cron = "${hapbang.chatbot.sync.weekly-cron:0 0 10 * * MON}", zone = "Asia/Seoul")
    void weekly() {
        chatbotSyncUseCase.runWeekly();
    }

    @Scheduled(cron = "${hapbang.chatbot.sync.retry-cron:0 0 12 * * MON}", zone = "Asia/Seoul")
    void retry() {
        chatbotSyncUseCase.runRetry();
    }
}
