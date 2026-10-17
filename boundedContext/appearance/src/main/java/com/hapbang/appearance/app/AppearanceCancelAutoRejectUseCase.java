package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceCancelRequest;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestRejectedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연 취소 요청 자동 거절 (배치). 방송 예정 일시까지 호스트가 응답하지 않은 취소 요청을 거절하고 출연을 유지한다 (AGENTS.md).
 * <ul>
 *     <li>출연마다 [트랜잭션] 출연 배타 잠금 → 아직 처리 중이고 기한이 지났는지 다시 확인 → 자동 거절 + 출연 {@code ESTABLISHED}.
 *     HP는 움직이지 않는다.</li>
 *     <li>그사이 호스트가 응답했으면 건너뛴다. 출연마다 예외를 잡아 다음 출연을 계속 처리한다 (최대 {@code batchSize}건).</li>
 *     <li>게스트에게 결과를 알리는 이벤트를 발행한다 ({@code autoRejected = true}, ISSUE.md APR-31 K7).</li>
 * </ul>
 * 트랜잭션은 같은 클래스 안 처리라 {@link TransactionTemplate}으로 연다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceCancelAutoRejectUseCase {

    private final AppearanceSupport appearanceSupport;
    private final AppearanceCancelRequestSupport appearanceCancelRequestSupport;
    private final AppearanceTitleSupport appearanceTitleSupport;
    private final AppearanceBatchProperties batchProperties;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 자동 거절한 취소 요청 수
     */
    public int rejectDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> appearanceIds = appearanceCancelRequestSupport.findDueAppearanceIds(now,
                batchProperties.batchSize());
        int rejected = 0;
        for (Long appearanceId : appearanceIds) {
            try {
                Boolean done = transactionTemplate.execute(status -> autoReject(appearanceId, now));
                if (Boolean.TRUE.equals(done)) {
                    rejected++;
                }
            } catch (RuntimeException exception) {
                log.warn("출연 취소 요청 자동 거절 중 예외, 다음 출연으로 넘어간다 appearanceId={}", appearanceId, exception);
            }
        }
        if (rejected > 0) {
            log.info("출연 취소 요청 자동 거절 count={}", rejected);
        }
        return rejected;
    }

    /** 자동 거절한다. 그사이 호스트가 응답했으면 false */
    private boolean autoReject(Long appearanceId, LocalDateTime now) {
        Appearance appearance = appearanceSupport.getForUpdate(appearanceId);
        if (!appearance.isCancelRequestDue(now)) {
            return false;
        }
        AppearanceCancelRequest cancelRequest = appearanceCancelRequestSupport.getPending(appearanceId);
        cancelRequest.autoReject(now);
        appearance.keepAfterCancelRejected();
        appearanceSupport.flushOrConflict();
        eventPublisher.publishEvent(new AppearanceCancelRequestRejectedEvent(
                cancelRequest.toDto(appearanceTitleSupport.titleOf(appearance), appearance.getScheduledAt()), true));
        return true;
    }
}
