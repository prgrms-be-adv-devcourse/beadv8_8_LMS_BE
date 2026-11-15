package com.hapbang.auxiliary.chatbot.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.IndexTaskRunner;
import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 색인 요청 실행 배치(#718 공통 기반). 실행 주기만 정하고 처리는 {@link IndexTaskRunner}가 한다.
 * 여러 pod가 함께 돌아도 요청을 나눠 가져가므로 실행 락을 잡지 않는다.
 */
@Slf4j
@Component
@ConditionalOnSchedulingEnabled
@RequiredArgsConstructor
class IndexTaskBatch {

    private final IndexTaskRunner runner;

    @Scheduled(initialDelayString = "${hapbang.chatbot.index.task.poll-interval:5s}",
            fixedDelayString = "${hapbang.chatbot.index.task.poll-interval:5s}")
    void run() {
        try {
            runner.runOnce();
        } catch (RuntimeException e) {
            // DB에 닿지 못한 경우 등. 요청은 남아 있어 다음 주기에 다시 가져간다
            log.warn("색인 요청을 가져오지 못했다. 다음 주기에 다시 한다", e);
        }
    }

    @Scheduled(cron = "${hapbang.chatbot.index.task.purge-cron:0 50 4 * * *}", zone = "Asia/Seoul")
    void purge() {
        try {
            runner.purgeDone();
        } catch (RuntimeException e) {
            log.warn("끝난 색인 요청을 지우지 못했다. 다음 날 다시 한다", e);
        }
    }
}
