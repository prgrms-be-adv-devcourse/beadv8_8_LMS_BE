package com.hapbang.chat.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 채팅 이벤트 Kafka 실행 중 재발행(kafka.md "발행"). Kafka로 보내지 못해 발행 기록({@code event_publication})이 미완료로 남은
 * 알림센터용 단체방 이벤트를 다시 보낸다. 재기동 때의 재발행({@code republish-outstanding-events-on-restart})만으로는 브로커 장애 동안의
 * 이벤트가 다음 배포까지 머무르므로 주기적으로 다시 보낸다.
 * <ul>
 *     <li>대상은 {@link ChatExternalEvents#isExternalized}가 고른 채팅 이벤트 중 발행한 지 대기 시간이 지난 기록이다.</li>
 *     <li>한 번에 최대 {@link ChatExternalEvents#REPUBLISH_BATCH_SIZE}건, 오래된 기록부터 보낸다.</li>
 *     <li>두 번 가더라도 알림센터가 사건 ID로 거른다(분산 락 없음, chat-app은 replica 1개).</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatEventRepublisher {

    private final IncompleteEventPublications incompleteEventPublications;
    private final Clock clock;
    private final Duration pendingGrace;
    private final int batchSize;

    @Autowired
    public ChatEventRepublisher(IncompleteEventPublications incompleteEventPublications) {
        this(incompleteEventPublications, Clock.systemUTC(), ChatExternalEvents.REPUBLISH_PENDING_GRACE,
                ChatExternalEvents.REPUBLISH_BATCH_SIZE);
    }

    ChatEventRepublisher(IncompleteEventPublications incompleteEventPublications, Clock clock, Duration pendingGrace,
            int batchSize) {
        this.incompleteEventPublications = incompleteEventPublications;
        this.clock = clock;
        this.pendingGrace = pendingGrace;
        this.batchSize = batchSize;
    }

    /**
     * @return 다시 보낸 기록 수
     */
    public int republishDue() {
        Instant publishedBefore = clock.instant().minus(pendingGrace);
        AtomicInteger selected = new AtomicInteger();
        incompleteEventPublications.resubmitIncompletePublications(publication ->
                ChatExternalEvents.isExternalized(publication.getEvent())
                        && publication.getPublicationDate().isBefore(publishedBefore)
                        && selected.incrementAndGet() <= batchSize);
        int republished = Math.min(selected.get(), batchSize);
        if (republished > 0) {
            log.warn("Kafka로 못 보낸 채팅 이벤트 재발행 count={}, waiting={}, batchSize={}", republished,
                    selected.get() - republished, batchSize);
        }
        return republished;
    }
}
