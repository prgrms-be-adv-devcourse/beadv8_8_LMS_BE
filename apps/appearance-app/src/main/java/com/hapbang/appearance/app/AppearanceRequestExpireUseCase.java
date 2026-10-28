package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.HpTaskReason;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 수락 기한 만료 (배치). 응답 대기 중이고 수락 기한이 지난 요청서를 {@code EXPIRED}로 바꾸고 보류 HP 반환 작업을 기록·실행한다.
 * <ul>
 *     <li>요청서마다 [트랜잭션] 만료 + 반환 작업 기록 (커밋) → [트랜잭션 밖] 반환 작업 1회 실행. 거절·응답 전 취소와 같은 흐름이다.</li>
 *     <li>그사이 상태가 바뀐 요청서는 건너뛴다. 수락·거절·취소는 기한이 지나면 막히므로 만료와 동시에 일어나지 않는다.</li>
 *     <li>보류 재확인 배치가 기한이 지난 요청서를 {@code REQUESTED}로 정리한 경우도 여기서 만료된다 (ISSUE.md APR-26 S5).</li>
 *     <li>한 번에 최대 {@code batchSize}건. 요청서마다 예외를 잡아 다음 요청서를 계속 처리한다.</li>
 * </ul>
 * 트랜잭션은 같은 클래스 안 처리라 {@link TransactionTemplate}으로 연다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceRequestExpireUseCase {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceRequestReleaseRecorder appearanceRequestReleaseRecorder;
    private final HpTaskExecuteUseCase hpTaskExecuteUseCase;
    private final AppearanceBatchProperties batchProperties;
    private final TransactionTemplate transactionTemplate;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 만료한 요청서 수
     */
    public int expireDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> requestIds = appearanceRequestSupport.findExpiredIds(now, batchProperties.batchSize());
        int expired = 0;
        for (Long requestId : requestIds) {
            try {
                Long taskId = transactionTemplate.execute(status -> expire(requestId, now));
                if (taskId == null) {
                    continue;
                }
                expired++;
                hpTaskExecuteUseCase.execute(taskId);
            } catch (RuntimeException exception) {
                log.warn("수락 기한 만료 처리 중 예외, 다음 요청서로 넘어간다 requestId={}", requestId, exception);
            }
        }
        if (expired > 0) {
            log.info("수락 기한 만료 count={}", expired);
        }
        return expired;
    }

    /** 만료하고 반환 작업을 기록한다. 그사이 상태가 바뀌었으면 null */
    private Long expire(Long requestId, LocalDateTime now) {
        AppearanceRequest request = appearanceRequestSupport.getById(requestId);
        if (!request.isExpirable(now)) {
            return null;
        }
        request.expire(now);
        return appearanceRequestReleaseRecorder.applyAndRecordRelease(request, HpTaskReason.REQUEST_EXPIRED, now);
    }
}
