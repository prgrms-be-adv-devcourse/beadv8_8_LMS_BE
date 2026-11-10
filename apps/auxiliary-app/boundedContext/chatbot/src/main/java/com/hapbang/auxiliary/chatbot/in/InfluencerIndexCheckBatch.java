package com.hapbang.auxiliary.chatbot.in;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.InfluencerIndexChecker;
import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 인플루언서 색인 정합 점검 (#719). 회원 싱크(:15) 뒤 매시 :45에 돈다({@code hapbang.chatbot.recommend.influencer.check-cron}).
 * 요청만 적고 끝나므로 배치 스레드를 오래 쓰지 않는다. 실패하면 다음 정시에 다시 하고, {@code check-alert-after-failures}번 연속 실패하면
 * {@code [CHATBOT_INFLUENCER_CHECK_FAILED]} ERROR로 알린다(이 pod가 떠 있는 동안 센다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnSchedulingEnabled
class InfluencerIndexCheckBatch {

    private final InfluencerIndexChecker checker;
    private final ChatbotInfluencerRecommendProperties properties;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    @Scheduled(cron = "${hapbang.chatbot.recommend.influencer.check-cron:0 45 * * * *}", zone = "Asia/Seoul")
    void check() {
        try {
            checker.check();
            consecutiveFailures.set(0);
        } catch (RuntimeException e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= properties.checkAlertAfterFailures()) {
                log.error("[CHATBOT_INFLUENCER_CHECK_FAILED] 인플루언서 색인 정합 점검이 연속으로 실패했다 failures={}", failures, e);
            } else {
                log.warn("인플루언서 색인 정합 점검이 실패했다. 다음 정시에 다시 한다 failures={}", failures, e);
            }
        }
    }
}
