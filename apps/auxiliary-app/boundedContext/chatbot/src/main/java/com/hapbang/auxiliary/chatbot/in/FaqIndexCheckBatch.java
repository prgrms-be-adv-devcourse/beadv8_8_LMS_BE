package com.hapbang.auxiliary.chatbot.in;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.FaqIndexer;
import com.hapbang.auxiliary.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FAQ 색인 주기 점검(#715). 색인이 실패했거나 비었거나(ES 재설치 등) 바뀐 FAQ가 남아 있으면 다시 색인한다.
 * 할 일이 없으면 ES에 index·문서 수만 묻고 끝난다. 시작 시 맞추기와 같은 락을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnSchedulingEnabled
class FaqIndexCheckBatch {

    private final FaqIndexer indexer;
    private final FaqJobRunner jobRunner;

    @Scheduled(initialDelayString = "${hapbang.chatbot.faq.index-check-interval:10m}",
            fixedDelayString = "${hapbang.chatbot.faq.index-check-interval:10m}")
    void check() {
        try {
            jobRunner.run("index-check", indexer::sync);
        } catch (RuntimeException e) {
            log.warn("FAQ 색인 점검이 실패했다. 다음 점검에서 다시 한다", e);
        }
    }
}
