package com.hapbang.appearance.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.config.AppearanceHpReconciliationProperties;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.HpReconciliationRule;
import com.hapbang.appearance.domain.HpReconciliationRun;
import com.hapbang.appearance.domain.HpReconciliationStatus;
import com.hapbang.appearance.domain.HpReconciliationTrigger;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldQueryCommand;
import com.hapbang.shared.billing.dto.HoldStateDto;
import com.hapbang.shared.billing.dto.HoldStatePageDto;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 서비스 간 HP 대사 (#581). 요청서·모집글·출연 상태와 지갑의 보류·지급 기록을 비교해 어긋난 건을 남긴다.
 * <p>
 * 대사 주체는 업무 모듈이고 지갑은 조회 API만 준다(지갑 #577). <b>고치지 않는다</b>. 정정은 원인을 확인한 관리자가
 * 기존 수단(HP 작업 다시 시도 #397, 보류 무효화 #536)으로 한다.
 * <ol>
 *     <li>지갑 보류 목록: 저장한 커서 뒤에 바뀐 보류를 끝까지 읽는다(지갑 쪽 변경 수집, 지갑에만 있는 보류 찾기)</li>
 *     <li>출연 쪽 변경: 비교 범위({@code lookback}) 안에 대상·출연·HP 작업이 바뀐 요청서·모집글</li>
 *     <li>둘을 합쳐 500개씩 지갑에 조회하고 {@link HpReconciliationComparer}로 비교한다. 진행 중인 건(보류 결과 대기,
 *     대기 중인 HP 작업, 유예 시간 안의 변경)은 빼고, 격리된 HP 작업이 있는 건은 표시만 한다.
 *     어긋남이 나온 건은 다시 읽어, 비교하는 사이에 바뀌었으면(다른 배치가 작업을 실행) 이번에는 남기지 않는다</li>
 *     <li>지갑 조회가 실패하면(회로 열림·결과 불명·거절) 그 실행을 {@code INCOMPLETE}로 끝내고 커서를 옮기지 않는다.
 *     그때까지 찾은 어긋남은 남긴다. 다음 실행이 같은 범위를 다시 본다</li>
 * </ol>
 * 동시에 두 번 실행하지 않는다. 배치는 실행 중이면 건너뛰고, 관리자 실행은 409다.
 * <ul>
 *     <li>같은 앱: 앱 메모리의 실행 중 표시로 막는다</li>
 *     <li>배포 중 옛 pod와 새 pod가 겹칠 때(maxSurge 1): DB에 {@code RUNNING} 실행이 있으면 다른 pod가 하고 있는 것으로 보고 시작하지
 *     않는다. 두 실행이 함께 끝나면 커서 저장이 충돌해 뒤에 끝난 실행을 기록하지 못한다({@code @Version}). 다만 확인과 시작 사이에는
 *     잠그지 않아, 두 pod의 배치가 같은 순간에 시작하면 함께 돌 수 있다(매일 04:00 한 번이라 드묾)</li>
 *     <li>앱이 멈춰 {@code RUNNING}으로 남은 실행은 실행을 시작할 때 {@code abandon-after}보다 오래된 것만 정리하고 중단 횟수를 올린다.
 *     그 안의 실행은 다른 pod가 하고 있을 수 있어 두고, 이번 실행을 시작하지 않는다(#821)</li>
 * </ul>
 * replica를 늘리면 단일 실행 장치를 먼저 둔다.
 * 배치는 시스템이 실행하므로 Facade를 거치지 않고 이 UseCase를 직접 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HpReconciliationUseCase {

    /** 어긋난 건 경고 로그 태그. Loki에서 이 문자열로 찾는다 */
    static final String MISMATCH_LOG_TAG = "[HP_RECONCILIATION_MISMATCH]";

    /** 결과를 기록하지 못해 미완료로 끝낸 실행의 사유 앞부분 (#821) */
    static final String FINISH_FAILED_REASON = "결과 기록 실패: ";

    private final HpReconciliationSupport hpReconciliationSupport;
    private final HpReconciliationMetrics hpReconciliationMetrics;
    private final BillingClient billingClient;
    private final AppearanceHpReconciliationProperties properties;
    @Qualifier(AppearanceConfig.CLOCK)
    private final Clock clock;
    // 빈(싱글턴)이 가진 실행 중 표시라 배치와 관리자 요청 사이에서 공유된다
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 매일 배치. 이미 실행 중이면 건너뛴다.
     *
     * @return 끝난 실행. 건너뛰었으면 null
     */
    public HpReconciliationRun runScheduled() {
        if (!running.compareAndSet(false, true)) {
            log.info("HP 대사가 이미 실행 중이라 이번 배치를 건너뛴다");
            return null;
        }
        try {
            return run(HpReconciliationTrigger.SCHEDULED, null);
        } finally {
            running.set(false);
        }
    }

    /**
     * 관리자 수동 실행. 끝날 때까지 기다려 결과를 돌려준다.
     *
     * @throws AppearanceException 이미 실행 중({@code HP_RECONCILIATION_RUNNING})
     */
    public HpReconciliationRun runManual(Long adminId) {
        if (!running.compareAndSet(false, true)) {
            throw new AppearanceException(AppearanceErrorCode.HP_RECONCILIATION_RUNNING);
        }
        try {
            HpReconciliationRun run = run(HpReconciliationTrigger.MANUAL, adminId);
            if (run == null) {
                throw new AppearanceException(AppearanceErrorCode.HP_RECONCILIATION_RUNNING);
            }
            return run;
        } finally {
            running.set(false);
        }
    }

    /** @return 끝난 실행. 다른 pod가 실행 중이라 시작하지 않았으면 null */
    private HpReconciliationRun run(HpReconciliationTrigger trigger, Long adminId) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        abandonLeftOverRuns(startedAt);
        List<HpReconciliationRun> otherRuns = hpReconciliationSupport.findRunning();
        if (!otherRuns.isEmpty()) {
            warnBlocked(trigger, otherRuns);
            return null;
        }
        Long runId = hpReconciliationSupport.start(trigger, adminId, startedAt);
        Progress progress = new Progress();
        String failureReason = null;
        try {
            if (billingClient.isCircuitOpen()) {
                throw new WalletUnavailableException("지갑 회로가 열려 있음");
            }
            for (HpTaskRefType refType : HpTaskRefType.values()) {
                reconcile(refType, startedAt, progress);
            }
        } catch (WalletUnavailableException exception) {
            failureReason = exception.getMessage();
        } catch (RuntimeException exception) {
            log.error("HP 대사 중 예외로 실행을 멈춘다 runId={}", runId, exception);
            failureReason = "예외: " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        // 결과를 기록하지 못하면(커서 버전 충돌, DB 순간 장애) 바로 미완료로 끝낸다. RUNNING으로 두면 abandon-after 동안
        // 같은 pod에서도 다시 실행할 수 없다. 그새 다른 pod가 먼저 정리했으면 그 pod가 중단 횟수를 올렸으므로 지표를 남기지 않는다 (#821)
        HpReconciliationRun run;
        boolean ownResult = true;
        try {
            run = hpReconciliationSupport.finish(runId, progress.mismatches, progress.checked, progress.dead,
                    failureReason, progress.cursors, LocalDateTime.now(clock));
        } catch (RuntimeException exception) {
            log.error("HP 대사 결과를 기록하지 못해 실행을 미완료로 끝낸다 runId={}", runId, exception);
            ownResult = hpReconciliationSupport.abandonIfRunning(runId,
                    FINISH_FAILED_REASON + exception.getClass().getSimpleName(), LocalDateTime.now(clock));
            run = hpReconciliationSupport.findRun(runId);
        }
        // 기록하지 못한 어긋남도 경고 로그로는 남긴다
        logMismatches(run, progress);
        if (ownResult) {
            recordResult(run, progress);
        } else {
            log.warn("다른 pod가 먼저 정리한 HP 대사라 지표를 남기지 않는다 runId={} status={} reason={}", runId,
                    run.getStatus(), run.getFailureReason());
        }
        return run;
    }

    /** 실행 중인 다른 실행 때문에 시작하지 않는다. 그날 배치가 통째로 빠지므로 막은 실행을 남긴다 */
    private static void warnBlocked(HpReconciliationTrigger trigger, List<HpReconciliationRun> otherRuns) {
        List<Long> runIds = new ArrayList<>();
        List<LocalDateTime> startedAts = new ArrayList<>();
        for (HpReconciliationRun other : otherRuns) {
            runIds.add(other.getId());
            startedAts.add(other.getStartedAt());
        }
        log.warn("다른 pod가 HP 대사를 실행 중이라 이번 실행을 시작하지 않는다 trigger={} runIds={} startedAts={}", trigger,
                runIds, startedAts);
    }

    /**
     * 앱이 멈춰 실행 중으로 남은 실행을 미완료로 끝내고 중단 횟수를 올린다. 횟수를 올리지 않으면 alert "대사 중단"이 알지 못한다(#821).
     * 새로 뜬 앱에서 처음 만드는 counter면 첫 수집 값이 이미 1이다. alert "대사 중단"은 새로 생긴 series도 중단으로 친다(#804)
     */
    private void abandonLeftOverRuns(LocalDateTime now) {
        List<Long> abandonedIds = hpReconciliationSupport.abandonRunningStartedBefore(
                now.minus(properties.abandonAfter()), now);
        if (!abandonedIds.isEmpty()) {
            hpReconciliationMetrics.recordRuns(HpReconciliationStatus.INCOMPLETE, abandonedIds.size());
            log.warn("앱이 멈춰 끝나지 못한 HP 대사를 미완료로 정리했다 runIds={}", abandonedIds);
        }
    }

    private void reconcile(HpTaskRefType refType, LocalDateTime startedAt, Progress progress) {
        BillingReferenceType billingType = BillingReferenceType.valueOf(refType.name());
        Map<Long, HoldStateDto> listed = readChangedWalletHolds(refType, billingType, progress);
        Set<Long> refIds = new TreeSet<>(
                hpReconciliationSupport.findChangedRefIds(refType, startedAt.minus(properties.lookback())));
        refIds.addAll(listed.keySet());
        LocalDateTime settleCutoff = startedAt.minus(properties.settleGrace());
        List<Long> all = List.copyOf(refIds);
        for (int from = 0; from < all.size(); from += properties.pageSize()) {
            List<Long> chunk = all.subList(from, Math.min(from + properties.pageSize(), all.size()));
            compareChunk(refType, billingType, chunk, listed, settleCutoff, progress);
        }
    }

    /** 커서 뒤에 바뀐 지갑 보류를 끝까지 읽는다. 다 읽은 커서는 실행이 완료될 때 저장한다 */
    private Map<Long, HoldStateDto> readChangedWalletHolds(HpTaskRefType refType, BillingReferenceType billingType,
                                                           Progress progress) {
        Map<Long, HoldStateDto> listed = new LinkedHashMap<>();
        HpReconciliationSupport.Cursor cursor = hpReconciliationSupport.cursorOf(refType);
        HoldStatePageDto page;
        do {
            HpReconciliationSupport.Cursor current = cursor;
            page = call("지갑 보류 목록 " + refType, () -> billingClient.listHolds(billingType,
                    current.updatedAfter(), current.afterId(), properties.pageSize()));
            for (HoldStateDto item : page.items()) {
                listed.put(item.referenceId(), item);
            }
            cursor = new HpReconciliationSupport.Cursor(page.nextUpdatedAfter(), page.nextAfterId());
        } while (page.hasMore());
        progress.cursors.put(refType, cursor);
        return listed;
    }

    private void compareChunk(HpTaskRefType refType, BillingReferenceType billingType, List<Long> chunk,
                              Map<Long, HoldStateDto> listed, LocalDateTime settleCutoff, Progress progress) {
        Map<Long, HpReconciliationTarget> targets = hpReconciliationSupport.loadTargets(refType, chunk);
        List<HpReconciliationTarget> toQuery = new ArrayList<>();
        for (Long refId : chunk) {
            HpReconciliationTarget target = targets.get(refId);
            if (target == null && !listed.containsKey(refId)) {
                // 출연 쪽에서 모은 ID인데 읽는 사이에 사라졌다 (출연은 지우지 않으므로 생기지 않아야 한다)
                log.warn("HP 대사: 출연 쪽에서 모은 대상을 읽지 못했다 {} {}", refType, refId);
            } else if (target == null) {
                // 출연에 없는 ID는 지갑 목록에서만 온 것이다
                progress.checked++;
                progress.add(refType, refId, HpReconciliationComparer.walletOnly(refType, refId, listed.get(refId)));
            } else if (isInProgress(target, settleCutoff)) {
                log.debug("HP 대사: 진행 중이라 뺀다 {} {}", refType, refId);
            } else if (target.hasDeadTask()) {
                progress.checked++;
                progress.dead++;
                progress.add(refType, refId, HpReconciliationComparer.dead(target));
            } else {
                toQuery.add(target);
            }
        }
        if (toQuery.isEmpty()) {
            return;
        }
        List<Long> queryIds = toQuery.stream().map(HpReconciliationTarget::refId).toList();
        Map<Long, HoldStateDto> states = call("지갑 보류 조회 " + refType,
                () -> billingClient.queryHolds(new HoldQueryCommand(billingType, queryIds))).stream()
                .collect(Collectors.toMap(HoldStateDto::referenceId, Function.identity(), (first, second) -> first));
        Map<Long, List<HpReconciliationComparer.Finding>> findings = new LinkedHashMap<>();
        for (HpReconciliationTarget target : toQuery) {
            HoldStateDto state = states.get(target.refId());
            if (state == null) {
                throw new WalletUnavailableException("지갑 보류 조회 응답에 " + refType + " " + target.refId() + "이 없음");
            }
            findings.put(target.refId(), HpReconciliationComparer.compare(target, state));
        }
        Set<Long> changedMeanwhile = findChangedMeanwhile(refType, toQuery, findings);
        for (HpReconciliationTarget target : toQuery) {
            if (changedMeanwhile.contains(target.refId())) {
                log.info("HP 대사: 비교하는 사이에 바뀌어 다음 실행에서 다시 본다 {} {}", refType, target.refId());
                continue;
            }
            progress.checked++;
            for (HpReconciliationComparer.Finding finding : findings.get(target.refId())) {
                progress.add(refType, target.refId(), finding);
            }
        }
    }

    /**
     * 어긋남이 나온 대상을 다시 읽어, 출연 쪽을 읽은 뒤 지갑을 조회하기 전에 바뀐 것을 찾는다.
     * 그사이 다른 배치(HP 작업 재시도·자동 확인)가 작업을 실행하면 출연 쪽은 옛 값, 지갑은 새 값이라 거짓 어긋남이 생긴다.
     * 마지막 변경 시각이 달라졌으면 이번에는 남기지 않는다(다음 실행이 다시 본다). 어긋남이 없는 대상은 다시 읽지 않는다.
     */
    private Set<Long> findChangedMeanwhile(HpTaskRefType refType, List<HpReconciliationTarget> compared,
                                           Map<Long, List<HpReconciliationComparer.Finding>> findings) {
        List<Long> suspicious = compared.stream().map(HpReconciliationTarget::refId)
                .filter(refId -> !findings.get(refId).isEmpty()).toList();
        if (suspicious.isEmpty()) {
            return Set.of();
        }
        Map<Long, HpReconciliationTarget> reloaded = hpReconciliationSupport.loadTargets(refType, suspicious);
        Set<Long> changed = new TreeSet<>();
        for (HpReconciliationTarget target : compared) {
            if (!suspicious.contains(target.refId())) {
                continue;
            }
            HpReconciliationTarget now = reloaded.get(target.refId());
            if (now == null || !now.changedAt().equals(target.changedAt())) {
                changed.add(target.refId());
            }
        }
        return changed;
    }

    /** 보류 결과 대기, 재시도를 기다리는 HP 작업, 유예 시간 안의 변경(대상·출연·작업)이 있으면 진행 중이다 */
    private static boolean isInProgress(HpReconciliationTarget target, LocalDateTime settleCutoff) {
        return target.holdPending() || target.hasPendingTask() || target.changedAt().isAfter(settleCutoff);
    }

    private <T> T call(String what, Supplier<BillingCallResult<T>> request) {
        BillingCallResult<T> result;
        try {
            result = request.get();
        } catch (BillingCallException exception) {
            throw new WalletUnavailableException(what + " 결과를 받지 못함(" + exception.getKind() + ")");
        }
        return switch (result) {
            case BillingCallResult.Succeeded<T> succeeded -> succeeded.value();
            case BillingCallResult.Failed<T> failed -> throw new WalletUnavailableException(
                    what + " 거절(" + failed.rawCode() + ", HTTP " + failed.httpStatus() + ")");
        };
    }

    private void logMismatches(HpReconciliationRun run, Progress progress) {
        for (HpReconciliationSupport.Mismatch mismatch : progress.mismatches) {
            HpReconciliationComparer.Finding finding = mismatch.finding();
            log.warn("{} rule={} runId={} referenceType={} referenceId={} appearanceId={} appearance=[{}] wallet=[{}]",
                    MISMATCH_LOG_TAG, finding.rule(), run.getId(), mismatch.refType(), mismatch.refId(),
                    finding.appearanceId(), finding.appearanceState(), finding.walletState());
        }
    }

    private void recordResult(HpReconciliationRun run, Progress progress) {
        hpReconciliationMetrics.recordRun(run.getStatus());
        if (run.getStatus() == HpReconciliationStatus.COMPLETED) {
            List<HpReconciliationRule> mismatchRules = new ArrayList<>();
            for (HpReconciliationSupport.Mismatch mismatch : progress.mismatches) {
                mismatchRules.add(mismatch.finding().rule());
            }
            hpReconciliationMetrics.updateMismatches(mismatchRules);
            hpReconciliationMetrics.updateLastCompleted(run.getFinishedAt());
            log.info("HP 대사 완료 runId={} checked={} mismatches={} dead={}", run.getId(), run.getCheckedCount(),
                    run.getMismatchCount(), run.getDeadCount());
        } else {
            log.warn("HP 대사를 끝까지 하지 못했다. 다음 실행에서 다시 본다 runId={} checked={} mismatches={} reason={}",
                    run.getId(), run.getCheckedCount(), run.getMismatchCount(), run.getFailureReason());
        }
    }

    /** 실행 하나의 진행 상황 */
    private static final class Progress {

        private final List<HpReconciliationSupport.Mismatch> mismatches = new ArrayList<>();
        private final Map<HpTaskRefType, HpReconciliationSupport.Cursor> cursors = new EnumMap<>(HpTaskRefType.class);
        private int checked;
        private int dead;

        private void add(HpTaskRefType refType, Long refId, HpReconciliationComparer.Finding finding) {
            mismatches.add(new HpReconciliationSupport.Mismatch(refType, refId, finding));
        }
    }

    /** 지갑 조회를 끝내지 못했다. 실행을 중단하고 다음 실행에서 다시 본다 */
    private static final class WalletUnavailableException extends RuntimeException {

        private WalletUnavailableException(String message) {
            super(message);
        }
    }
}
