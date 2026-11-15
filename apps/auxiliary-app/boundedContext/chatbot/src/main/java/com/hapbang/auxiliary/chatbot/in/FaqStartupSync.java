package com.hapbang.auxiliary.chatbot.in;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.FaqSyncService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 앱이 뜬 뒤 FAQ 시드를 DB에 맞추고 색인한다(#715).
 * <ul>
 *     <li>별도 스레드에서 돈다. 임베딩·ES가 느리거나 죽어도 기동과 다른 API를 막지 않는다(readiness는 이 작업을 기다리지 않는다).</li>
 *     <li>advisory lock으로 한 pod만 실행한다. 못 잡으면 건너뛴다(배포 중 겹친 다른 pod가 하는 중).</li>
 *     <li>실패해도 앱은 그대로다. 색인 실패는 주기 점검({@link FaqIndexCheckBatch})이나 다음 기동에서 다시 한다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hapbang.chatbot.faq.sync-on-startup", havingValue = "true", matchIfMissing = true)
class FaqStartupSync {

    private final FaqSyncService syncService;
    private final FaqJobRunner jobRunner;

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        Thread.ofVirtual().name("chatbot-faq-sync").start(this::run);
    }

    void run() {
        try {
            jobRunner.run("startup", syncService::syncAll);
        } catch (RuntimeException e) {
            log.warn("시작할 때 FAQ를 맞추지 못했다. 다음 점검·기동에서 다시 한다", e);
        }
    }
}
