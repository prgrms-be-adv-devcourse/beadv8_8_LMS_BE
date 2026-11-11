package com.hapbang.auxiliary.chatbot.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.InfluencerIndexChecker;
import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;

/**
 * 인플루언서 색인 정합 점검 (#719). 회원 싱크(:15) 뒤 매시 :45에 돈다({@code hapbang.chatbot.recommend.influencer.check-cron}).
 * 요청만 적고 끝나므로 배치 스레드를 오래 쓰지 않는다. 실패 처리와 알림은 {@link InfluencerIndexChecker#checkOnSchedule()}가 한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnSchedulingEnabled
class InfluencerIndexCheckBatch {

    private final InfluencerIndexChecker checker;

    @Scheduled(cron = "${hapbang.chatbot.recommend.influencer.check-cron:0 45 * * * *}", zone = "Asia/Seoul")
    void check() {
        checker.checkOnSchedule();
    }
}
