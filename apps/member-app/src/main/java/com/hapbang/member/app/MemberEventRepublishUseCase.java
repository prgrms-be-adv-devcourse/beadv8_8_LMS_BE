package com.hapbang.member.app;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트 Kafka 재발행 (#421, kafka.md "발행": 발행하는 앱마다 반드시 둔다). Kafka로 보내지 못해 발행 기록이 미완료로 남은
 * 회원 이벤트를 앱이 떠 있는 동안 다시 보낸다.
 * <ul>
 *     <li>Modulith는 기동할 때만 미완료 기록을 다시 보낸다({@code republish-outstanding-events-on-restart}). broker가 producer
 *     재시도 시간(약 2분)보다 오래 멈췄다 돌아오면, 이 배치가 없을 때는 다음 배포까지 다른 앱의 회원 복제본과 지갑 생성이 밀린다.</li>
 *     <li>발행한 지 대기 시간({@code pendingGrace})이 지난 기록만 가져간다. 커밋 직후 보내는 중인 기록과 겹치지 않게 하기 위해서다.</li>
 *     <li>겹치거나 서버 여러 대가 같이 다시 보내 두 번 가거나 순서가 바뀌어도, 받는 쪽이 회원 버전({@code memberVersion})으로
 *     거른다(분산 락 없음, kafka.md).</li>
 *     <li>Kafka로 내보내는 회원 이벤트({@link MemberExternalEvents})만, 한 번에 최대 {@code batchSize}건, 오래된 기록부터 보낸다.
 *     나머지는 다음 주기에 보낸다.</li>
 * </ul>
 */
@Slf4j
@Service
public class MemberEventRepublishUseCase {

    private final IncompleteEventPublications incompleteEventPublications;
    private final Duration pendingGrace;
    private final int batchSize;

    public MemberEventRepublishUseCase(IncompleteEventPublications incompleteEventPublications,
                                       @Value("${member.event.republish-pending-grace:1m}") Duration pendingGrace,
                                       @Value("${member.event.republish-batch-size:100}") int batchSize) {
        this.incompleteEventPublications = incompleteEventPublications;
        this.pendingGrace = pendingGrace;
        this.batchSize = batchSize;
    }

    /**
     * @return 다시 보낸 기록 수
     */
    public int republishDue(Instant now) {
        Instant publishedBefore = now.minus(pendingGrace);
        AtomicInteger selected = new AtomicInteger();
        incompleteEventPublications.resubmitIncompletePublications(publication ->
                MemberExternalEvents.isExternalized(publication.getEvent())
                        && publication.getPublicationDate().isBefore(publishedBefore)
                        && selected.incrementAndGet() <= batchSize);
        int republished = Math.min(selected.get(), batchSize);
        if (republished > 0) {
            log.warn("Kafka로 못 보낸 회원 이벤트 재발행 count={}, waiting={}, batchSize={}", republished,
                    selected.get() - republished, batchSize);
        }
        return republished;
    }
}
