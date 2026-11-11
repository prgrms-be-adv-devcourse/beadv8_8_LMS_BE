package com.hapbang.auxiliary.chatbot.in;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.LectureIndexReconciler;
import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 강의 추천 색인 정합 점검 (#718). 앱이 뜬 뒤 한 번(처음 전체 색인), 그리고 매시 강의 싱크(:20) 뒤(:50, 설정값)에 돈다.
 * 실패해도 다음 점검에서 다시 한다. 색인 요청을 적기만 하므로 짧다(사본을 읽고 ES 문서 필드를 비교).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnSchedulingEnabled
class LectureIndexCheckBatch {

    private final LectureIndexReconciler reconciler;

    /** 기동과 readiness를 막지 않게 별도 스레드에서 돈다 */
    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        Thread.ofVirtual().name("chatbot-lecture-index-check").start(() -> run("startup"));
    }

    @Scheduled(cron = "${hapbang.chatbot.recommend.lecture.index-check-cron:0 50 * * * *}", zone = "Asia/Seoul")
    void check() {
        run("scheduled");
    }

    void run(String trigger) {
        if (!reconciler.available()) {
            return;
        }
        try {
            int requested = reconciler.check();
            if (requested > 0) {
                log.info("강의 추천 색인이 사본과 달라 색인을 요청했다 trigger={}, requested={}", trigger, requested);
            }
        } catch (RuntimeException e) {
            log.warn("강의 추천 색인 점검이 실패했다. 다음 점검에서 다시 한다 trigger={}", trigger, e);
        }
    }
}
