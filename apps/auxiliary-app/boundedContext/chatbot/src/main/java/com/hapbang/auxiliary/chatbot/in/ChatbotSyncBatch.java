package com.hapbang.auxiliary.chatbot.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.ChatbotSyncUseCase;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 챗봇 싱크 배치 (#716, #717). 실행 시각만 정하고 처리는 {@link ChatbotSyncUseCase}가 한다.
 * <p>
 * 종류마다 매시 한 번, 분을 나눠 돈다(원본 앱에 몰리지 않게). 실패·건너뜀은 다음 정시가 다시 한다. 따로 재시도 배치를 두지 않는다
 * (멘토 리뷰 #756 반영, 11/10 결정: 주 1회 → 매시).
 */
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class ChatbotSyncBatch {

    private final ChatbotSyncUseCase chatbotSyncUseCase;

    @Scheduled(cron = "${hapbang.chatbot.sync.cron.appearance:0 5 * * * *}", zone = "Asia/Seoul")
    void appearance() {
        chatbotSyncUseCase.runScheduled(SyncType.APPEARANCE);
    }

    @Scheduled(cron = "${hapbang.chatbot.sync.cron.appearance-review:0 10 * * * *}", zone = "Asia/Seoul")
    void appearanceReview() {
        chatbotSyncUseCase.runScheduled(SyncType.APPEARANCE_REVIEW);
    }

    @Scheduled(cron = "${hapbang.chatbot.sync.cron.member:0 15 * * * *}", zone = "Asia/Seoul")
    void member() {
        chatbotSyncUseCase.runScheduled(SyncType.MEMBER);
    }

    @Scheduled(cron = "${hapbang.chatbot.sync.cron.lecture:0 20 * * * *}", zone = "Asia/Seoul")
    void lecture() {
        chatbotSyncUseCase.runScheduled(SyncType.LECTURE);
    }

    @Scheduled(cron = "${hapbang.chatbot.sync.cron.lecture-purchase:0 25 * * * *}", zone = "Asia/Seoul")
    void lecturePurchase() {
        chatbotSyncUseCase.runScheduled(SyncType.LECTURE_PURCHASE);
    }

    @Scheduled(cron = "${hapbang.chatbot.sync.cron.cart-item:0 30 * * * *}", zone = "Asia/Seoul")
    void cartItem() {
        chatbotSyncUseCase.runScheduled(SyncType.CART_ITEM);
    }

    @Scheduled(cron = "${hapbang.chatbot.sync.cron.lecture-review:0 35 * * * *}", zone = "Asia/Seoul")
    void lectureReview() {
        chatbotSyncUseCase.runScheduled(SyncType.LECTURE_REVIEW);
    }
}
