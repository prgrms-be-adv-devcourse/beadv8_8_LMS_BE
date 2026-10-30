package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 알림 이벤트 Kafka 재발행 (배치, #393). Kafka로 보내지 못해 발행 기록이 미완료로 남은 알림 이벤트를 다시 보낸다.
 * <ul>
 *     <li>서버 재시작 때만 다시 보내면 브로커 장애 동안의 알림이 다음 배포까지 밀리므로 주기적으로 다시 보낸다.</li>
 *     <li>발행한 지 대기 시간({@code pendingGrace})이 지난 기록만 가져간다. 커밋 직후 보내는 중인 기록과 겹치지 않도록 하기 위해서다.
 *     겹치거나 서버 여러 대가 같이 다시 보내 두 번 가더라도 받는 쪽이 {@code (eventType, 대상 ID)}로 거른다(분산 락 없음, kafka.md).</li>
 *     <li>Kafka로 내보내는 알림 이벤트({@link AppearanceExternalEvents})만 다시 보낸다. 같은 JVM 리스너(회원 이벤트 등)의 미완료 기록은 건드리지 않는다.</li>
 *     <li>한 번에 최대 {@code batchSize}건, 오래된 기록부터 보낸다. 브로커가 오래 멈춘 뒤 쌓인 기록을 한꺼번에 보내지 않고 나머지는 다음 주기에 보낸다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceEventRepublishUseCase {

    private final IncompleteEventPublications incompleteEventPublications;
    private final AppearanceBatchProperties batchProperties;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 다시 보낸 기록 수
     */
    public int republishDue() {
        Instant publishedBefore = clock.instant().minus(batchProperties.pendingGrace());
        AtomicInteger selected = new AtomicInteger();
        incompleteEventPublications.resubmitIncompletePublications(publication ->
                AppearanceExternalEvents.isExternalized(publication.getEvent())
                        && publication.getPublicationDate().isBefore(publishedBefore)
                        && selected.incrementAndGet() <= batchProperties.batchSize());
        int republished = Math.min(selected.get(), batchProperties.batchSize());
        if (republished > 0) {
            log.warn("Kafka로 못 보낸 알림 이벤트 재발행 count={}, waiting={}", republished, selected.get() - republished);
        }
        return republished;
    }
}
