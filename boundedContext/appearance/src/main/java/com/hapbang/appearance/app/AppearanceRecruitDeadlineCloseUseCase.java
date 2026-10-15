package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceRecruit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 모집 기한 마감 (배치). 모집 중이고 마감 일시가 지난 모집글을 {@code CLOSED}(마감 방식 {@code DEADLINE})로 바꾸고,
 * 대기 중 지원 정리·반환 작업 기록 후 반환을 실행한다.
 * <ul>
 *     <li>모집글마다 [트랜잭션] 마감 + 지원 정리 + 반환 작업 기록 (커밋) → [트랜잭션 밖] 반환 작업 1회 실행. 조기 마감과 같은 흐름이다.</li>
 *     <li>그사이 상태가 바뀐 모집글은 건너뛴다. 지원·수락·조기 마감은 마감 일시가 지나면 막히므로 겹칠 일이 드물고,
 *     겹쳐서 버전 충돌이 나면 그 건만 건너뛰고 다음 주기에 다시 처리한다 (ISSUE.md APR-28 C8).</li>
 *     <li>보류 처리 중({@code HOLD_PENDING})인 모집글은 대상이 아니다. 보류 재확인 배치가 {@code RECRUITING}으로 정리하면
 *     다음 실행에서 마감된다. 보류 결과를 모르는 동안은 반환하지 않는다 (C5).</li>
 *     <li>한 번에 최대 {@code batchSize}건. 모집글마다 예외를 잡아 다음 모집글을 계속 처리한다.</li>
 * </ul>
 * 트랜잭션은 같은 클래스 안 처리라 {@link TransactionTemplate}으로 연다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceRecruitDeadlineCloseUseCase {

    private final AppearanceRecruitSupport appearanceRecruitSupport;
    private final AppearanceRecruitCloseRecorder appearanceRecruitCloseRecorder;
    private final HpTaskExecuteUseCase hpTaskExecuteUseCase;
    private final AppearanceBatchProperties batchProperties;
    private final TransactionTemplate transactionTemplate;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;

    /**
     * @return 마감한 모집글 수
     */
    public int closeDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> recruitIds = appearanceRecruitSupport.findDeadlinePassedIds(now, batchProperties.batchSize());
        int closed = 0;
        for (Long recruitId : recruitIds) {
            try {
                CloseResult result = transactionTemplate.execute(status -> close(recruitId, now));
                if (result == null || !result.closed()) {
                    continue;
                }
                closed++;
                if (result.releaseTaskId() != null) {
                    hpTaskExecuteUseCase.execute(result.releaseTaskId());
                }
            } catch (RuntimeException exception) {
                log.warn("모집 기한 마감 처리 중 예외, 다음 모집글로 넘어간다 recruitId={}", recruitId, exception);
            }
        }
        if (closed > 0) {
            log.info("모집 기한 마감 count={}", closed);
        }
        return closed;
    }

    /** 마감하고 뒷정리를 기록한다. 그사이 상태가 바뀌었으면 마감하지 않음 */
    private CloseResult close(Long recruitId, LocalDateTime now) {
        AppearanceRecruit recruit = appearanceRecruitSupport.getById(recruitId);
        if (!recruit.isDeadlinePassed(now)) {
            return CloseResult.SKIPPED;
        }
        recruit.closeByDeadline(now);
        Long releaseTaskId = appearanceRecruitCloseRecorder.closeApplicationsAndRecordRelease(recruit, now);
        return new CloseResult(true, releaseTaskId);
    }

    /** 마감했는지와 기록한 반환 작업 ID (반환할 금액이 없으면 null) */
    private record CloseResult(boolean closed, Long releaseTaskId) {

        static final CloseResult SKIPPED = new CloseResult(false, null);
    }
}
