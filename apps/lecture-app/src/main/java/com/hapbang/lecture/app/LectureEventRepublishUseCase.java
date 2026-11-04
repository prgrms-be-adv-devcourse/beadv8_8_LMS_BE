package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureEventRepublishProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 강의 이벤트 Kafka 재발행 (배치, #278, kafka.md "발행"). Kafka로 보내지 못해 발행 기록이 미완료로 남은 강의 이벤트를 다시 보낸다.
 * <ul>
 *     <li>서버 재시작 때만 다시 보내면 브로커 장애 동안의 결과 이벤트가 다음 배포까지 밀리므로 주기적으로 다시 보낸다.</li>
 *     <li>발행한 지 대기 시간({@code pendingGrace})이 지난 기록만 가져간다. 커밋 직후 보내는 중인 기록과 겹치지 않게 하기 위해서다.
 *     겹치거나 서버 여러 대가 같이 다시 보내 두 번 가더라도 받는 쪽(고객센터)이 문의 상태로 거른다(분산 락 없음).</li>
 *     <li>Kafka로 내보내는 강의 이벤트({@link LectureExternalEvents})만 다시 보낸다.</li>
 *     <li>한 번에 최대 {@code batchSize}건, 오래된 기록부터 보낸다. 나머지는 다음 주기에 보낸다.</li>
 * </ul>
 */
@Slf4j
@Service
public class LectureEventRepublishUseCase {

    private final IncompleteEventPublications incompleteEventPublications;
    private final LectureEventRepublishProperties properties;
    private final Clock clock;

    public LectureEventRepublishUseCase(IncompleteEventPublications incompleteEventPublications,
                                        LectureEventRepublishProperties properties,
                                        @Qualifier(LectureConfig.CLOCK) Clock clock) {
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
                LectureExternalEvents.isExternalized(publication.getEvent())
                        && publication.getPublicationDate().isBefore(publishedBefore)
                        && selected.incrementAndGet() <= properties.batchSize());
        int republished = Math.min(selected.get(), properties.batchSize());
        if (republished > 0) {
            log.warn("Kafka로 못 보낸 강의 이벤트 재발행 count={}, waiting={}, batchSize={}", republished,
                    selected.get() - republished, properties.batchSize());
        }
        return republished;
    }
}
