package com.hapbang.auxiliary.notification.app;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.ScheduleTimes;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 예약 배치(N10, ND-029·ND-134·ND-135·ND-142). 1분마다 {@code NotificationScheduleBatch}가 {@link #runOnce}를 부른다.
 * <ul>
 *     <li>여러 pod 중 한 곳만 실행한다({@link JobLock}, {@link NotificationJob#SCHEDULE_DISPATCH}). 잡지 못하면 그 회차를 건너뛴다.</li>
 *     <li>보낼 시각이 지난 "예약"을 정한 건수만큼 읽고, 예약마다 트랜잭션을 따로 연다({@link #dispatchOne}). 한 예약이 실패해도 다음 예약을
 *     보낸다. 실패한 예약은 "예약"으로 남아 다음 회차가 다시 보낸다. 서버가 꺼져 있던 동안 밀린 예약도 같은 길로 나간다(ND-134).</li>
 *     <li>한 예약의 처리: 예약 행을 잠그고 다시 읽는다 → 이미 끝났으면(취소가 먼저 커밋) 아무것도 하지 않는다 → 업무 기한이 지났으면
 *     "건너뜀"(ND-135·ND-144) → 알림 저장({@link NotificationWriter}, 같은 트랜잭션) → 받을 수 없는 회원이면 "건너뜀", 아니면 "보냄".
 *     알림 저장과 상태 변경 중 하나가 실패하면 둘 다 되돌아간다.</li>
 *     <li>잠금 순서는 예약 행 → 받는 사람 행(저장 서비스)이다. 리스너도 예약 행을 먼저 쓴다({@link ScheduleService#applyWith}).</li>
 *     <li>알림의 원본 이벤트 종류는 예약 종류 코드, 원본 대상 ID는 대상 ID다(ND-139). 같은 출연의 방송 임박과 출연 확인 부탁이 따로
 *     알림이 된다. 업무 기한 판정과 발생 시각은 행을 잠근 뒤 읽은 시각(보낸 시각)이다(ND-135·ND-148).</li>
 *     <li>회차마다 먼저 기한이 지난 "예약"을 건너뜀으로 끝내고, 보낼 예약은 기한이 남은 것만 고른다.</li>
 *     <li>실패한 예약은 분리된 트랜잭션에서 실패 횟수를 세고, 3번 연속이면 건너뜀으로 끝내며 ERROR를 남긴다(ND-147).</li>
 *     <li>발송 뒤 끝 상태이고 업무 기한 + 보존 일수가 지난 예약을 정한 건수만큼 지운다(ND-142).</li>
 * </ul>
 */
@Slf4j
@Service
public class ScheduleDispatcher {

    /** 예약 처리 결과 */
    public enum Outcome {
        /** 알림을 만들었다(같은 알림이 이미 있으면 새로 만들지 않았다) */
        SENT,
        /** 업무 기한이 지났거나 받을 수 없는 회원이라 알림 없이 끝냈다 */
        SKIPPED,
        /** 이미 끝난(취소 등) 예약이거나 정리돼 없었다 */
        NOT_SCHEDULED
    }

    /**
     * 한 회차의 결과
     *
     * @param ran 잠금을 잡아 실행했는지
     */
    public record RunResult(boolean ran, int sent, int skipped, int failed, int deleted) {

        static RunResult notRun() {
            return new RunResult(false, 0, 0, 0, 0);
        }
    }

    /** 같은 예약을 이 횟수만큼 연속으로 보내지 못하면 건너뜀으로 끝낸다(ND-147) */
    static final int MAX_FAILURES = 3;

    /** 예약 하나의 결과 지표(ND-150). alert "예약 알림 발송 실패"는 abandoned·record_failed를 본다(ND-151) */
    static final String OUTCOMES_METRIC = "hapbang.notification.schedule.outcomes";
    /** 회차 결과 지표(ND-150). alert는 failed를 본다(ND-151) */
    static final String RUNS_METRIC = "hapbang.notification.schedule.runs";

    /** 지표 {@value #OUTCOMES_METRIC}의 result 값 */
    enum Result {
        SENT, SKIPPED, RETRY, ABANDONED, RECORD_FAILED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final ScheduleStore store;
    private final NotificationWriter writer;
    private final TransactionTemplate transaction;
    /** 실패 횟수 기록 전용. 실패한 저장 트랜잭션과 분리한다(ND-147) */
    private final TransactionTemplate failureTransaction;
    private final JobLock jobLock;
    private final int batchSize;
    private final int cleanupBatchSize;
    private final int retentionDays;
    /** 지표가 없는 실행(지표 저장소 빈 없음)에서는 비어 있다 */
    private final Map<Result, Counter> outcomes = new EnumMap<>(Result.class);
    private final Counter runsCompleted;
    private final Counter runsFailed;

    public ScheduleDispatcher(ScheduleStore store, NotificationWriter writer, TransactionTemplate transaction,
            JobLock jobLock, ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${hapbang.notification.schedule.batch-size:100}") int batchSize,
            @Value("${hapbang.notification.schedule.cleanup-batch-size:100}") int cleanupBatchSize,
            @Value("${hapbang.notification.schedule.retention-days:7}") int retentionDays) {
        this.store = store;
        this.writer = writer;
        this.transaction = transaction;
        this.failureTransaction = new TransactionTemplate(transaction.getTransactionManager());
        this.failureTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jobLock = jobLock;
        this.batchSize = batchSize;
        this.cleanupBatchSize = cleanupBatchSize;
        this.retentionDays = retentionDays;
        // 처음부터 0으로 만들어 두어 첫 증가도 increase()로 잡힌다(ND-150). 0인 새 series는 alert 기준(> 0)에 걸리지 않는다
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            for (Result result : Result.values()) {
                outcomes.put(result, Counter.builder(OUTCOMES_METRIC).tag("result", result.tag()).register(registry));
            }
            this.runsCompleted = Counter.builder(RUNS_METRIC).tag("outcome", "completed").register(registry);
            this.runsFailed = Counter.builder(RUNS_METRIC).tag("outcome", "failed").register(registry);
        } else {
            this.runsCompleted = null;
            this.runsFailed = null;
        }
    }

    /**
     * 한 회차. 잠금을 잡지 못하면 실행하지 않는다. 회차 시작 시각으로 기한이 지난 "예약"을 건너뜀으로 끝내고 보낼 예약을 고른 뒤,
     * 예약마다 행을 잠근 뒤의 시각으로 다시 판정한다(#929 리뷰 2·3)
     *
     * @param clock 지금 시각. 운영은 시스템 시계, 시험은 정한 시계
     */
    public RunResult runOnce(Clock clock) {
        Optional<JobLock.Lease> lease;
        try {
            lease = jobLock.tryAcquire(NotificationJob.SCHEDULE_DISPATCH);
        } catch (RuntimeException e) {
            // 잠금 시도 실패(DB 오류)도 회차 실패로 센다. 잠금 경합(빈 값)은 세지 않는다(ND-150)
            increment(runsFailed);
            throw e;
        }
        if (lease.isEmpty()) {
            log.debug("다른 실행이 예약 배치 잠금을 쥐고 있어 이번 회차를 건너뛰었다");
            return RunResult.notRun();
        }
        try (JobLock.Lease ignored = lease.get()) {
            RunResult result = runLocked(clock);
            increment(runsCompleted);
            return result;
        } catch (RuntimeException e) {
            increment(runsFailed);
            throw e;
        }
    }

    private RunResult runLocked(Clock clock) {
        Instant selectedAt = clock.instant();
        // 기한이 지난 예약이 보낼 예약 고르기의 앞자리를 차지하지 않게 먼저 끝낸다(ND-135·ND-144)
        Integer expired = transaction.execute(status -> store.skipExpired(selectedAt, batchSize));
        int sent = 0;
        int skipped = expired == null ? 0 : expired;
        int failed = 0;
        count(Result.SKIPPED, skipped);
        List<Long> due = store.findDueIds(selectedAt, batchSize);
        for (long id : due) {
            try {
                switch (dispatchOne(id, clock)) {
                    case SENT -> {
                        sent++;
                        count(Result.SENT, 1);
                    }
                    case SKIPPED -> {
                        skipped++;
                        count(Result.SKIPPED, 1);
                    }
                    case NOT_SCHEDULED -> {
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                count(recordFailure(id, clock.instant(), e), 1);
            }
        }
        int deleted = cleanUp(clock.instant());
        if (!due.isEmpty() || skipped > 0 || deleted > 0) {
            log.info("예약 배치를 실행했다 due={}, sent={}, skipped={}, failed={}, deleted={}", due.size(), sent,
                    skipped, failed, deleted);
        }
        return new RunResult(true, sent, skipped, failed, deleted);
    }

    /**
     * 예약 하나를 한 트랜잭션에서 처리한다. 실패하면 알림과 상태 변경이 함께 되돌아가고 예외를 던진다
     *
     * @param clock 행을 잠근 뒤 이 시계로 지금 시각을 읽어 업무 기한을 판정하고 발생 시각으로 쓴다(ND-135·ND-148)
     */
    public Outcome dispatchOne(long scheduleId, Clock clock) {
        return transaction.execute(status -> dispatchLocked(scheduleId, clock));
    }

    /**
     * 보내지 못한 예약의 실패 횟수를 센다(ND-147). 실패한 저장 트랜잭션은 이미 롤백됐고, 횟수는 그와 분리된 새 트랜잭션
     * ({@code REQUIRES_NEW})에서 기록한다. {@value #MAX_FAILURES}번 연속이면 같은 트랜잭션에서 "건너뜀"으로 끝내고 사람이 보게 ERROR를
     * 남긴다. 그 전에는 다음 회차로 미룬 배치 항목이라 WARN이다(docs/dev/logging.md 레벨 표). 횟수 기록마저 실패하면 예약은 그대로
     * "예약"으로 남고, alert 대상이라 표식을 붙인 ERROR를 남긴다(ND-150)
     *
     * @return 지표에 셀 결과. 건너뜀으로 끝냈으면 abandoned, 기록마저 실패했으면 record_failed, 그 밖에는 retry(ND-150)
     */
    private Result recordFailure(long scheduleId, Instant now, RuntimeException cause) {
        try {
            Integer count = failureTransaction.execute(status -> {
                OptionalInt recorded = store.recordFailure(scheduleId, now);
                if (recorded.isPresent() && recorded.getAsInt() >= MAX_FAILURES) {
                    store.finish(scheduleId, ScheduleStore.Status.SKIPPED, now);
                }
                return recorded.isPresent() ? recorded.getAsInt() : null;
            });
            if (count != null && count >= MAX_FAILURES) {
                log.error("[NOTIFICATION_SCHEDULE_FAILED] 예약 알림을 {}번 연속 보내지 못해 건너뜀으로 끝냈다 scheduleId={}",
                        count, scheduleId, cause);
                return Result.ABANDONED;
            }
            log.warn("예약 알림을 보내지 못했다. 예약으로 남아 다음 회차에 다시 보낸다 scheduleId={}, failureCount={}",
                    scheduleId, count, cause);
            return Result.RETRY;
        } catch (RuntimeException e) {
            e.addSuppressed(cause);
            // alert "예약 알림 발송 실패"가 보는 결과라 사람이 볼 ERROR로 남긴다(ND-150). 횟수가 쌓이지 않아 건너뜀에 이르지 못할 수 있다
            log.error("[NOTIFICATION_SCHEDULE_FAILED] 예약 알림 실패 횟수를 기록하지 못했다. 예약으로 남아 다음 회차에 다시 보낸다 "
                    + "scheduleId={}", scheduleId, e);
            return Result.RECORD_FAILED;
        }
    }

    private void count(Result result, int amount) {
        Counter counter = outcomes.get(result);
        if (counter != null && amount > 0) {
            counter.increment(amount);
        }
    }

    private static void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    /** 끝난 예약을 정리한다(ND-142). 정한 건수까지만 지운다 */
    public int cleanUp(Instant now) {
        Integer deleted = transaction.execute(status -> store.deleteFinished(now, retentionDays, cleanupBatchSize));
        return deleted == null ? 0 : deleted;
    }

    private Outcome dispatchLocked(long scheduleId, Clock clock) {
        Optional<ScheduleStore.Due> found = store.lockById(scheduleId);
        if (found.isEmpty() || found.get().status() != ScheduleStore.Status.SCHEDULED) {
            log.debug("이미 끝났거나 없는 예약이라 보내지 않았다 scheduleId={}", scheduleId);
            return Outcome.NOT_SCHEDULED;
        }
        // 잠금을 기다린 뒤의 시각으로 판정하고 그 시각에 보낸다
        Instant now = clock.instant();
        ScheduleStore.Due due = found.get();
        if (ScheduleTimes.isPast(due.businessDeadline(), now)) {
            store.finish(scheduleId, ScheduleStore.Status.SKIPPED, now);
            log.info("업무 기한이 지나 예약 알림을 보내지 않았다 scheduleId={}, kind={}, businessDeadline={}", scheduleId,
                    due.kind(), due.businessDeadline());
            return Outcome.SKIPPED;
        }
        NotificationWriteResult result = writer.save(draft(due, now));
        if (result.outcome() == NotificationWriteResult.Outcome.SKIPPED_RECEIVER) {
            store.finish(scheduleId, ScheduleStore.Status.SKIPPED, now);
            return Outcome.SKIPPED;
        }
        store.finish(scheduleId, ScheduleStore.Status.SENT, now);
        log.info("예약 알림을 보냈다 scheduleId={}, kind={}, receiverUserId={}, outcome={}", scheduleId, due.kind(),
                due.receiverUserId(), result.outcome());
        return Outcome.SENT;
    }

    /** 발생 시각은 보낸 시각이다(ND-148). 늦게 보낸 알림도 20개 보존에서 바로 밀려나지 않는다 */
    private static NotificationDraft draft(ScheduleStore.Due due, Instant sentAt) {
        Map<String, Object> payload = new LinkedHashMap<>(due.payload());
        String target = String.valueOf(due.targetId());
        return new NotificationDraft(due.receiverUserId(), due.kind().kind(), due.kind().kind(), target,
                due.kind().targetType(), target, payload, sentAt);
    }
}
