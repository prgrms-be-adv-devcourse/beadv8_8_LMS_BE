package com.hapbang.chat.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.chat.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

// 채팅 이벤트 Kafka 실행 중 재발행 배치. 처리는 ChatEventRepublisher가 한다. 주기는 코드 상수다(ChatExternalEvents)
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChatEventRepublishBatch {

    private final ChatEventRepublisher chatEventRepublisher;

    @Scheduled(cron = ChatExternalEvents.REPUBLISH_CRON, zone = "Asia/Seoul")
    void run() {
        chatEventRepublisher.republishDue();
    }
}
