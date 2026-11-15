package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.SyncMismatch;
import com.hapbang.auxiliary.chatbot.domain.SyncMismatchKind;
import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncRunStatus;
import com.hapbang.auxiliary.chatbot.domain.SyncTally;
import com.hapbang.auxiliary.chatbot.domain.SyncTrigger;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.exception.ChatbotErrorCode;
import com.hapbang.auxiliary.chatbot.exception.ChatbotException;
import com.hapbang.auxiliary.chatbot.out.CopyWrite;
import com.hapbang.auxiliary.chatbot.out.MaskedMemberStore;
import com.hapbang.auxiliary.chatbot.out.MissingCopy;
import com.hapbang.auxiliary.chatbot.out.SyncMismatchRepository;
import com.hapbang.auxiliary.chatbot.out.SyncRunRepository;
import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.shared.appearance.out.AppearanceApiCallException;

import lombok.extern.slf4j.Slf4j;

/**
 * 싱크 (#716, #717). 원본 키셋 내부 API를 처음부터 끝까지 읽어 사본의 어긋남을 바로잡는다. 첫 실행이 곧 초기 적재다.
 * <ul>
 *     <li>정기 실행은 종류마다 매시다({@code ChatbotSyncBatch}). 실패·건너뜀은 다음 정시가 다시 하고, 그 종류가
 *     {@code failure-alert-after}(기본 3시간) 동안 성공하지 못했으면 {@code [CHATBOT_SYNC_FAILED]} ERROR로 알린다.</li>
 *     <li>회차 기록은 {@code run-retention}(기본 30일)이 지나면 그 종류의 회차가 끝날 때 지운다(어긋남 행도 함께).</li>
 *     <li>종류마다 따로 실행·기록한다. 한 종류의 실패가 다른 종류를 막지 않는다.</li>
 *     <li>pod 사이 겹침은 종류별 advisory lock({@link ChatbotJob})으로 막는다. 배치는 못 잡으면 {@code SKIPPED} 회차를 남기고,
 *     관리자 실행은 409다. 관리자 실행은 요청 스레드에서 락을 잡고 그 락을 비동기 작업에 넘긴다.</li>
 *     <li>항목마다 적용기({@link CopyApplier})로 반영한다(이벤트와 같은 판정). 트랜잭션은 항목 반영 한 문장과 회차 기록마다 따로다.</li>
 *     <li>원본 없음 표시는 끝까지 성공한 회차의 끝에만 한다. 실패한 회차는 표시하지 않는다.</li>
 *     <li>첫 실행(그 종류의 성공 회차가 없음)은 원본 전체를 넣으므로 경고하지 않고, 어긋남 행도 남기지 않는다(건수만).</li>
 *     <li>마스킹 회원의 데이터는 적용기가 버리고({@code discarded}, 어긋남·경고에서 뺀다), 회차 끝마다 남은 행을 한 번 더 지운다(#717).</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatbotSyncUseCase {

    private final Map<SyncType, SyncTarget<?>> targets = new EnumMap<>(SyncType.class);
    private final JobLock jobLock;
    private final SyncRunRepository syncRunRepository;
    private final SyncMismatchRepository syncMismatchRepository;
    private final MaskedMemberStore maskedMemberStore;
    private final ChatbotSyncProperties properties;
    private final SyncMetrics metrics;
    private final TaskExecutor taskExecutor;
    private final Clock clock;

    ChatbotSyncUseCase(List<SyncTarget<?>> targets, JobLock jobLock, SyncRunRepository syncRunRepository,
            SyncMismatchRepository syncMismatchRepository, MaskedMemberStore maskedMemberStore,
            ChatbotSyncProperties properties, SyncMetrics metrics,
            @Qualifier(ChatbotConfig.SYNC_EXECUTOR) TaskExecutor taskExecutor, @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        targets.forEach(target -> this.targets.put(target.type(), target));
        if (this.targets.size() != SyncType.values().length) {
            throw new IllegalStateException("싱크 종류마다 SyncTarget이 하나씩 있어야 합니다: " + this.targets.keySet());
        }
        this.jobLock = jobLock;
        this.syncRunRepository = syncRunRepository;
        this.syncMismatchRepository = syncMismatchRepository;
        this.maskedMemberStore = maskedMemberStore;
        this.properties = properties;
        this.metrics = metrics;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
    }

    /**
     * 정기 배치(종류마다 매시): 그 종류를 실행한다. 예외를 던지지 않는다. 회차 기록조차 못 하는 장애(DB 연결 불가 등)는
     * 로그만 남기고 다음 정시가 다시 한다.
     */
    public void runScheduled(SyncType type) {
        try {
            runBatch(type, SyncTrigger.SCHEDULED);
        } catch (RuntimeException e) {
            log.error("[CHATBOT_SYNC_FAILED] 싱크 회차를 시작·기록하지 못했다 type={}", type, e);
        }
    }

    /**
     * 배치로 한 종류를 실행하고 끝날 때까지 기다린다. 락을 잡지 못하면 실행하지 않고 {@code SKIPPED} 회차를 남긴다.
     * 예외를 던지지 않는다(실패는 {@code FAILED} 회차).
     */
    public SyncRun runBatch(SyncType type, SyncTrigger trigger) {
        SyncTarget<?> target = targets.get(type);
        Optional<JobLock.Lease> lease;
        try {
            lease = jobLock.tryAcquire(target.job());
        } catch (JobLock.JobLockException e) {
            if (longWithoutSuccess(type)) {
                log.error("[CHATBOT_SYNC_FAILED] 실행 락을 시도하지 못해 싱크를 건너뛴다. 오래 성공하지 못했다 type={}, trigger={}, "
                        + "alertAfter={}", type, trigger, properties.failureAlertAfter(), e);
            } else {
                log.warn("실행 락을 시도하지 못해 싱크를 건너뛴다 type={}, trigger={}", type, trigger, e);
            }
            return skipped(type, trigger, "실행 락을 시도하지 못했습니다: " + rootMessage(e));
        }
        if (lease.isEmpty()) {
            return skipped(type, trigger, "다른 실행이 락을 쥐고 있습니다.");
        }
        try (JobLock.Lease ignored = lease.get()) {
            closeOrphans(type);
            return execute(target, syncRunRepository.save(SyncRun.start(type, trigger, null, now())));
        }
    }

    /**
     * 관리자 실행. 요청 스레드에서 락을 잡고 회차를 만든 뒤 비동기로 실행한다. 락은 실행이 끝나면 푼다.
     *
     * @return 시작한 회차(상태 {@code RUNNING})
     * @throws ChatbotException 실행 중({@code SYNC_ALREADY_RUNNING}), 락을 시도하지 못함({@code SYNC_LOCK_UNAVAILABLE})
     */
    public SyncRun startManual(SyncType type, Long adminId) {
        SyncTarget<?> target = targets.get(type);
        Optional<JobLock.Lease> found;
        try {
            found = jobLock.tryAcquire(target.job());
        } catch (JobLock.JobLockException e) {
            throw new ChatbotException(ChatbotErrorCode.SYNC_LOCK_UNAVAILABLE, e);
        }
        JobLock.Lease lease = found.orElseThrow(() -> new ChatbotException(ChatbotErrorCode.SYNC_ALREADY_RUNNING));
        SyncRun run = null;
        try {
            closeOrphans(type);
            run = syncRunRepository.save(SyncRun.start(type, SyncTrigger.ADMIN, adminId, now()));
            SyncRun started = run;
            taskExecutor.execute(() -> {
                try (JobLock.Lease ignored = lease) {
                    execute(target, started);
                }
            });
            log.info("관리자 싱크를 시작했다 type={}, runId={}, adminId={}", type, run.getId(), adminId);
            return run;
        } catch (RuntimeException e) {
            lease.close();
            if (run != null) {
                run.fail(new SyncTally(), "실행을 시작하지 못했습니다: " + rootMessage(e), now());
                syncRunRepository.save(run);
            }
            throw e;
        }
    }

    // 락을 쥔 지금 그 종류의 RUNNING 회차는 끝을 기록하지 못한 이전 실행이다(DB 장애, pod 종료). 실패로 닫는다
    private void closeOrphans(SyncType type) {
        for (SyncRun orphan : syncRunRepository.findByTypeAndStatus(type, SyncRunStatus.RUNNING)) {
            orphan.fail(new SyncTally(), "실행이 끝을 기록하지 못했습니다(다음 실행이 정리).", now());
            syncRunRepository.save(orphan);
            log.warn("끝을 기록하지 못한 싱크 회차를 실패로 닫았다 type={}, runId={}, startedAt={}", type, orphan.getId(),
                    orphan.getStartedAt());
        }
    }

    private SyncRun skipped(SyncType type, SyncTrigger trigger, String reason) {
        SyncRun run = syncRunRepository.save(SyncRun.skipped(type, trigger, reason, now()));
        log.info("싱크를 건너뛰었다 type={}, trigger={}, reason={}", type, trigger, reason);
        metrics.record(run, Duration.ZERO);
        return run;
    }

    /**
     * 회차를 끝까지 실행하고 결과를 기록한다. 예외를 던지지 않는다. 결과 기록까지 실패하면 회차가 RUNNING으로 남고,
     * 다음 실행이 락을 잡은 뒤 실패로 닫는다({@link #closeOrphans}).
     */
    private <T> SyncRun execute(SyncTarget<T> target, SyncRun run) {
        long startNanos = System.nanoTime();
        SyncType type = target.type();
        SyncTally tally = new SyncTally();
        Mismatches mismatches = new Mismatches(run.getId(), type, 0);
        try {
            boolean firstRun = !syncRunRepository.existsByTypeAndStatus(type, SyncRunStatus.SUCCEEDED);
            mismatches = new Mismatches(run.getId(), type, firstRun ? 0 : properties.mismatchMaxRows());
            log.info("싱크를 시작한다 type={}, runId={}, trigger={}, firstRun={}", type, run.getId(), run.getTrigger(),
                    firstRun);
            Long afterId = null;
            do {
                SyncTarget.Page<T> page = target.fetch(afterId, properties.pageSize());
                verify(target, page, afterId, properties.pageSize());
                for (T item : page.items()) {
                    CopyWrite write = target.apply(item, run.getId());
                    tally.add(write.outcome());
                    mismatches.add(target.idOf(item), write.outcome().mismatch(), write.ourVersion(), target.versionOf(item));
                }
                afterId = page.nextAfterId();
            } while (afterId != null);
            List<MissingCopy> missing = target.markMissing(run.getId(), run.getStartedAt());
            for (MissingCopy row : missing) {
                mismatches.add(row.sourceId(), SyncMismatchKind.MISSING_IN_SOURCE, row.ourVersion(), null);
            }
            boolean warning = !firstRun && tally.warns(properties.warningRatio(), properties.warningMinChanged());
            run.succeed(tally, missing.size(), warning, now());
        } catch (RuntimeException e) {
            run.fail(tally, describe(e), now());
            // 한두 번의 실패는 다음 정시가 다시 한다. 오래 성공하지 못했으면 사람이 봐야 한다
            if (longWithoutSuccess(type)) {
                log.error("[CHATBOT_SYNC_FAILED] 싱크가 중간에 멈췄다. 오래 성공하지 못했다 type={}, runId={}, trigger={}, "
                        + "scanned={}, alertAfter={}", type, run.getId(), run.getTrigger(), tally.scanned(),
                        properties.failureAlertAfter(), e);
            } else {
                log.warn("싱크가 중간에 멈췄다 type={}, runId={}, trigger={}, scanned={}", type, run.getId(), run.getTrigger(),
                        tally.scanned(), e);
            }
        }
        try {
            syncRunRepository.save(run);
            syncMismatchRepository.saveAll(mismatches.rows);
            syncMismatchRepository.deleteOutsideRecentRuns(type, properties.mismatchKeepRuns());
            int expired = syncRunRepository.deleteFinishedBefore(type, now().minus(properties.runRetention()));
            if (expired > 0) {
                log.info("보관 기간이 지난 싱크 회차를 지웠다 type={}, deleted={}", type, expired);
            }
        } catch (RuntimeException e) {
            log.error("[CHATBOT_SYNC_RECORD_FAILED] 싱크 회차 결과를 기록하지 못했다 type={}, runId={}, status={}", type,
                    run.getId(), run.getStatus(), e);
        }
        purgeMasked(type, run);
        metrics.record(run, Duration.ofNanos(System.nanoTime() - startNanos));
        if (run.isWarning()) {
            // 이벤트 반영이 크게 어긋났다(소비 중단 등). 싱크가 고쳤지만 원인은 사람이 봐야 한다
            log.error("[CHATBOT_SYNC_MISMATCH] 싱크가 넣거나 고친 비율이 크다 type={}, runId={}, scanned={}, inserted={}, fixed={}",
                    type, run.getId(), run.getScanned(), run.getInserted(), run.getFixed());
        }
        log.info("싱크를 마쳤다 type={}, runId={}, status={}, scanned={}, inserted={}, fixed={}, oursNewer={}, markedMissing={}, "
                        + "discarded={}", type, run.getId(), run.getStatus(), run.getScanned(), run.getInserted(), run.getFixed(),
                run.getOursNewer(), run.getMarkedMissing(), run.getDiscarded());
        return run;
    }

    // 그 종류가 알림 기준 시간 동안 성공하지 못했는지(첫 배포 뒤 아직 성공이 없는 것도 포함)
    // 확인조차 못 하면(DB 장애) 알린다
    private boolean longWithoutSuccess(SyncType type) {
        try {
            return !syncRunRepository.existsByTypeAndStatusAndStartedAtGreaterThanEqual(type, SyncRunStatus.SUCCEEDED,
                    now().minus(properties.failureAlertAfter()));
        } catch (RuntimeException e) {
            return true;
        }
    }

    // 마스킹 회원의 행이 어떤 경로로든 남았으면 지운다(#717 멱등 정리). 실패해도 회차 결과는 그대로 두고 다음 회차가 다시 한다
    private void purgeMasked(SyncType type, SyncRun run) {
        try {
            long purged = maskedMemberStore.purgeAll();
            if (purged > 0) {
                log.info("마스킹 회원의 남은 사본 행을 지웠다 type={}, runId={}, purged={}", type, run.getId(), purged);
            }
        } catch (RuntimeException e) {
            log.warn("마스킹 회원 정리를 하지 못했다 type={}, runId={}", type, run.getId(), e);
        }
    }

    // 키셋 계약: 요청 크기 이하, ID는 afterId보다 크고 엄격히 오름차순, nextAfterId가 있으면 비어 있지 않은 페이지의 마지막 ID.
    // 어기면 그 페이지를 반영하지 않고 회차를 실패로 끝낸다(잘못된 응답으로 반복하거나 원본 없음을 잘못 표시하지 않는다)
    private static <T> void verify(SyncTarget<T> target, SyncTarget.Page<T> page, Long afterId, int size) {
        List<T> items = page.items();
        if (items == null || items.size() > size) {
            throw new IllegalStateException("원본 키셋 응답이 계약과 다릅니다: 항목 수");
        }
        long previous = afterId == null ? Long.MIN_VALUE : afterId;
        for (T item : items) {
            long id = target.idOf(item);
            if (id <= previous) {
                throw new IllegalStateException("원본 키셋 응답이 계약과 다릅니다: ID 순서 afterId=" + afterId + ", id=" + id);
            }
            previous = id;
        }
        Long next = page.nextAfterId();
        if (next != null && (items.isEmpty() || next != previous)) {
            throw new IllegalStateException("원본 키셋 응답이 계약과 다릅니다: nextAfterId=" + next);
        }
    }

    private static String describe(RuntimeException e) {
        if (e instanceof AppearanceApiCallException call) {
            return "출연 내부 API 호출 실패 reason=" + call.getReason() + ", status=" + call.getStatus() + ", code="
                    + call.getCode();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private LocalDateTime now() {
        return CopyTimes.now(clock);
    }

    /** 회차에 남길 어긋남 행. 최대 개수를 넘으면 버린다(건수는 회차에 남는다) */
    private static final class Mismatches {

        private final Long runId;
        private final SyncType type;
        private final int max;
        private final List<SyncMismatch> rows = new ArrayList<>();

        private Mismatches(Long runId, SyncType type, int max) {
            this.runId = runId;
            this.type = type;
            this.max = max;
        }

        private void add(long sourceId, SyncMismatchKind kind, Long ourVersion, Long sourceVersion) {
            if (kind != null && rows.size() < max) {
                rows.add(SyncMismatch.create(runId, type, sourceId, kind, ourVersion, sourceVersion));
            }
        }
    }
}
