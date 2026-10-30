package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.out.event.AppearanceNotificationExternalizationConfig;

import lombok.RequiredArgsConstructor;

/**
 * 알림 이벤트 Kafka 재발행 (배치, #393). Kafka로 보내지 못해 발행 기록이 미완료로 남은 알림 이벤트를 다시 보낸다.
 * <ul>
 *     <li>서버 재시작 때만 다시 보내면 브로커 장애 동안의 알림이 다음 배포까지 밀리므로 주기적으로 다시 보낸다.</li>
 *     <li>발행한 지 대기 시간({@code pendingGrace})이 지난 기록만 가져간다. 커밋 직후 보내는 중인 기록과 겹치지 않도록 하기 위해서다.
 *     겹쳐서 두 번 가더라도 같은 {@code eventId}라 받는 쪽이 거른다.</li>
 *     <li>Kafka로 내보내는 알림 이벤트만 다시 보낸다. 같은 JVM 리스너(회원 이벤트 등)의 미완료 기록은 건드리지 않는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceNotificationRepublishUseCase {

    private final IncompleteEventPublications incompleteEventPublications;
    private final AppearanceBatchProperties batchProperties;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    public void republishDue() {
        Instant publishedBefore = clock.instant().minus(batchProperties.pendingGrace());
        incompleteEventPublications.resubmitIncompletePublications(publication ->
                AppearanceNotificationExternalizationConfig.isExternalized(publication.getEvent())
                        && publication.getPublicationDate().isBefore(publishedBefore));
    }
}
