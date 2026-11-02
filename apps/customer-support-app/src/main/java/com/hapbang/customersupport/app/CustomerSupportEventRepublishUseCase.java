package com.hapbang.customersupport.app;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 고객센터 이벤트 Kafka 재발행 (배치, #279, kafka.md "발행"). Kafka로 보내지 못해 발행 기록이 미완료로 남은 판단·철회 요청을 다시 보낸다.
 * <ul>
 *     <li>서버 재시작 때만 다시 보내면 브로커 장애 동안의 요청이 다음 배포까지 처리 중에 머무르므로 주기적으로 다시 보낸다.</li>
 *     <li>발행한 지 대기 시간({@code pendingGrace})이 지난 기록만 가져간다. 두 번 가더라도 강의가 명령 ID로 거른다(분산 락 없음).</li>
 *     <li>한 번에 최대 {@code batchSize}건, 오래된 기록부터 보낸다.</li>
 * </ul>
 */
@Slf4j
@Service
public class CustomerSupportEventRepublishUseCase {

    private final IncompleteEventPublications incompleteEventPublications;
    private final CustomerSupportBatchProperties properties;
    private final Clock clock;

    @Autowired
    public CustomerSupportEventRepublishUseCase(IncompleteEventPublications incompleteEventPublications,
                                                CustomerSupportBatchProperties properties) {
        this(incompleteEventPublications, properties, Clock.systemUTC());
    }

    CustomerSupportEventRepublishUseCase(IncompleteEventPublications incompleteEventPublications,
                                         CustomerSupportBatchProperties properties, Clock clock) {
        this.incompleteEventPublications = incompleteEventPublications;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @return 다시 보낸 기록 수
     */
    public int republishDue() {
        Instant publishedBefore = clock.instant().minus(properties.pendingGrace());
        AtomicInteger selected = new AtomicInteger();
        incompleteEventPublications.resubmitIncompletePublications(publication ->
                CustomerSupportExternalEvents.isExternalized(publication.getEvent())
                        && publication.getPublicationDate().isBefore(publishedBefore)
                        && selected.incrementAndGet() <= properties.batchSize());
        int republished = Math.min(selected.get(), properties.batchSize());
        if (republished > 0) {
            log.warn("Kafka로 못 보낸 고객센터 이벤트 재발행 count={}, waiting={}, batchSize={}", republished,
                    selected.get() - republished, properties.batchSize());
        }
        return republished;
    }
}
