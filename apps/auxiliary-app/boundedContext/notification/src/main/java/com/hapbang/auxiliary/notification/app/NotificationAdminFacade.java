package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;

import lombok.extern.slf4j.Slf4j;

/**
 * 알림 관리자 기능(ND-079·ND-092). 관리자만 쓴다. 회원이 쓰는 알림 API의 관리자 403(ND-052)과 반대 방향이다. global 보안 설정도
 * {@code /api/v1/*}{@code /admin/**}을 관리자로 막는다.
 * <p>
 * Backfill 재실행은 비동기다. 요청 스레드에서 잠금을 잡고 Redis에 RUNNING을 쓴 뒤 실행 ID를 돌려주고, 실제 실행은 관리자 Backfill
 * 실행기({@link NotificationAdminBackfillExecutorConfig})에서 한다. 실행 상태는 실제 실행과 맞게 쓴다.
 * <ul>
 *     <li>잠금을 시도하지 못하거나(DB 장애) 실행을 넘기지 못하면 503 {@code BACKFILL_FAILED}, 시작 기록을 쓰지 못하면 503
 *     {@code BACKFILL_STATUS_UNAVAILABLE}이다. 어느 경우든 잠금을 풀고 실행하지 않는다.</li>
 *     <li>실행 중에는 페이지마다 갱신 시각을 쓴다. 갱신 쓰기 실패는 실행을 멈추지 않는다(갱신이 오래 없으면 조회가 UNKNOWN).</li>
 *     <li>끝나면 COMPLETED(결과)나 FAILED(실패 코드)를 쓴 뒤 잠금을 푼다. 끝 상태를 쓰지 못하면 WARN이고, 조회가 5분 뒤 UNKNOWN으로
 *     드러난다.</li>
 * </ul>
 */
@Slf4j
@Service
public class NotificationAdminFacade {

    private final NotificationUserBackfillService backfill;
    private final BackfillRunStore runs;
    private final SimpleAsyncTaskExecutor executor;

    public NotificationAdminFacade(NotificationUserBackfillService backfill, BackfillRunStore runs,
            @Qualifier(NotificationAdminBackfillExecutorConfig.EXECUTOR) SimpleAsyncTaskExecutor executor) {
        this.backfill = backfill;
        this.runs = runs;
        this.executor = executor;
    }

    /**
     * 회원 복제본 Backfill을 비동기로 시작한다.
     *
     * @return 시작한 실행(RUNNING)
     * @throws NotificationException 다른 실행이 돌고 있으면 {@code BACKFILL_RUNNING}(409), 시작 기록을 쓰지 못하면
     *                               {@code BACKFILL_STATUS_UNAVAILABLE}(503), 잠금을 시도하지 못했거나 실행을 넘기지 못하면
     *                               {@code BACKFILL_FAILED}(503)
     */
    @PreAuthorize("hasRole('ADMIN')")
    public BackfillRun startUserBackfill() {
        JobLock.Lease lease;
        try {
            lease = backfill.acquire();
        } catch (NotificationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Backfill 잠금을 시도하지 못해 시작하지 않았다", e);
            throw new NotificationException(NotificationErrorCode.BACKFILL_FAILED);
        }
        BackfillRun started = BackfillRun.running(UUID.randomUUID().toString(), Instant.now());
        try {
            runs.save(started);
        } catch (RuntimeException e) {
            lease.close();
            log.warn("Backfill 시작 기록을 쓰지 못해 실행하지 않았다 runId={}", started.runId(), e);
            throw new NotificationException(NotificationErrorCode.BACKFILL_STATUS_UNAVAILABLE);
        }
        try {
            executor.execute(() -> execute(started, lease));
        } catch (RuntimeException e) {
            lease.close();
            saveQuietly(started.failed(NotificationErrorCode.BACKFILL_FAILED.name(), Instant.now()));
            log.warn("Backfill 실행을 넘기지 못해 시작하지 않았다 runId={}", started.runId(), e);
            throw new NotificationException(NotificationErrorCode.BACKFILL_FAILED);
        }
        log.info("관리자 Backfill을 시작했다 runId={}", started.runId());
        return started;
    }

    /**
     * @throws NotificationException 없거나 보관 기간이 지났으면 {@code BACKFILL_RUN_NOT_FOUND}(404)
     */
    @PreAuthorize("hasRole('ADMIN')")
    public BackfillRun findUserBackfill(String runId) {
        return runs.find(runId).map(run -> run.viewedAt(Instant.now()))
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.BACKFILL_RUN_NOT_FOUND));
    }

    private void execute(BackfillRun started, JobLock.Lease lease) {
        // 페이지 콜백(람다)에서 갱신한 상태를 이어 받는 holder다. 실행 스레드 하나만 쓴다
        AtomicReference<BackfillRun> current = new AtomicReference<>(started);
        try {
            BackfillRun finished;
            try {
                BackfillResult result = backfill.runLocked(() -> touch(current));
                finished = current.get().completed(result, Instant.now());
            } catch (RuntimeException e) {
                log.warn("관리자 Backfill을 마치지 못했다 runId={}", started.runId(), e);
                finished = current.get().failed(NotificationErrorCode.BACKFILL_FAILED.name(), Instant.now());
            }
            // 끝 상태를 쓴 뒤 잠금을 푼다. 다음 시작은 이 실행의 끝 상태가 보인 뒤에만 잠금을 잡는다
            saveQuietly(finished);
        } finally {
            lease.close();
        }
    }

    private void touch(AtomicReference<BackfillRun> current) {
        saveQuietly(current.updateAndGet(run -> run.touched(Instant.now())));
    }

    /** 상태 쓰기 실패는 실행을 바꾸지 않는다. 남은 RUNNING은 갱신이 멈춰 5분 뒤 조회에서 UNKNOWN이 된다 */
    private void saveQuietly(BackfillRun run) {
        try {
            runs.save(run);
        } catch (RuntimeException e) {
            log.warn("Backfill 실행 상태를 쓰지 못했다 runId={}, status={}", run.runId(), run.status(), e);
        }
    }
}
