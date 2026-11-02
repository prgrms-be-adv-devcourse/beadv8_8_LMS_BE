package com.hapbang.billing.app;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

// Kafka로 못 보낸 지갑 이벤트를 실행 중에 다시 보낸다 (#497, kafka.md "발행": 발행 앱마다 반드시 둔다).
// Modulith는 기동할 때만 미완료 발행 기록을 다시 보내므로(republish-outstanding-events-on-restart), 서버가 계속 떠 있는 동안
// Kafka가 잠깐 끊겼던 기록은 이 배치가 보낸다. 방금 발행돼 아직 보내는 중인 기록과 겹치지 않게 유예 시간(pending-grace)이
// 지난 것만, 한 번에 batch-size개까지 보낸다. replica 여럿이 같은 기록을 다시 보내도 받는 쪽 멱등이 흡수하므로 분산 락은 두지 않는다.
@Slf4j
@Service
public class BillingEventRepublishUseCase {

    private final IncompleteEventPublications incompleteEventPublications;
    private final Duration pendingGrace;
    private final int batchSize;

    public BillingEventRepublishUseCase(IncompleteEventPublications incompleteEventPublications,
            @Value("${hapbang.billing.event.republish-pending-grace:1m}") Duration pendingGrace,
            @Value("${hapbang.billing.event.republish-batch-size:100}") int batchSize) {
        this.incompleteEventPublications = incompleteEventPublications;
        this.pendingGrace = pendingGrace;
        this.batchSize = batchSize;
    }

    // 다시 보낸 기록 수를 돌려준다.
    public int republishDue(Instant now) {
        Instant publishedBefore = now.minus(pendingGrace);
        AtomicInteger selected = new AtomicInteger();
        incompleteEventPublications.resubmitIncompletePublications(publication ->
                BillingExternalEvents.isExternalized(publication.getEvent())
                        && publication.getPublicationDate().isBefore(publishedBefore)
                        && selected.incrementAndGet() <= batchSize);
        int republished = Math.min(selected.get(), batchSize);
        if (republished > 0) {
            log.warn("Kafka로 못 보낸 지갑 이벤트 재발행 count={}, waiting={}, batchSize={}", republished,
                    selected.get() - republished, batchSize);
        }
        return republished;
    }
}
