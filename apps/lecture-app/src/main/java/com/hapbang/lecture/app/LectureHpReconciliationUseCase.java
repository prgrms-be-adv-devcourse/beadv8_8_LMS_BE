package com.hapbang.lecture.app;

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

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.config.LectureHpReconciliationProperties;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureHpReconciliationRule;
import com.hapbang.lecture.domain.LectureHpReconciliationRun;
import com.hapbang.lecture.domain.LectureHpReconciliationStatus;
import com.hapbang.lecture.domain.LectureHpReconciliationTrigger;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldQueryCommand;
import com.hapbang.shared.billing.dto.HoldStateDto;
import com.hapbang.shared.billing.dto.HoldStatePageDto;
import com.hapbang.shared.billing.out.BillingApiClient;
import com.hapbang.shared.billing.out.BillingCallException;
import com.hapbang.shared.billing.out.BillingCallResult;

import lombok.extern.slf4j.Slf4j;

/**
 * 서비스 간 HP 대사 (#580, D74). 주문 항목 상태와 지갑의 보류·지급 기록을 비교해 어긋난 건을 남긴다.
 * <p>
 * 대사 주체는 업무 모듈이고 지갑은 조회 API만 준다(지갑 #577). <b>고치지 않는다</b>. 정정은 원인을 확인한 관리자가
 * 기존 수단(HP 작업 재시도 D57 (9), 보류 무효화 D73)으로 한다.
 * <ol>
 *     <li>지갑 보류 목록: 저장한 커서 뒤에 바뀐 보류를 끝까지 읽는다(지갑 쪽 변경 수집, 지갑에만 있는 보류 찾기)</li>
 *     <li>강의 쪽 변경: 비교 범위({@code lookback}) 안에 HP 작업이 바뀐 주문 항목</li>
 *     <li>둘을 합쳐 500개씩 지갑에 조회하고 {@link LectureHpReconciliationComparer}로 비교한다. 진행 중인 항목(결제 단계,
 *     대기·실행 중인 작업, 유예 시간 안의 변경)은 빼고, DEAD 작업이 있는 항목은 표시만 한다.
 *     어긋남이 나온 항목은 다시 읽어, 비교하는 사이에 바뀌었으면(구매 취소·작업 실행) 이번에는 남기지 않는다</li>
 *     <li>지갑 조회가 실패하면(회로 열림·결과 불명·거절) 그 실행을 {@code INCOMPLETE}로 끝내고 커서를 옮기지 않는다.
 *     그때까지 찾은 어긋남은 남긴다. 다음 실행이 같은 범위를 다시 본다</li>
 * </ol>
 * 같은 앱에서 동시에 두 번 실행하지 않는다. 배치는 실행 중이면 건너뛰고, 관리자 실행은 409다.
 * 실행 중 표시는 앱 메모리이고 강의 앱 1대(replicas 1, maxSurge 0)라 pod가 겹치지 않는다는 전제다. replica를 늘리면 단일 실행 장치를 먼저 둔다.
 * 배치는 시스템이 실행하므로 Facade를 거치지 않고 이 UseCase를 직접 부른다.
 */
@Slf4j
@Service
public class LectureHpReconciliationUseCase {

    /** 어긋난 건 경고 로그 태그. Loki에서 이 문자열로 찾는다 */
    static final String MISMATCH_LOG_TAG = "[LECTURE_HP_RECONCILIATION_MISMATCH]";

    private static final BillingReferenceType REFERENCE_TYPE = BillingReferenceType.LECTURE_ORDER_ITEM;

    private final LectureHpReconciliationSupport support;
    private final LectureHpReconciliationMetrics metrics;
    private final BillingApiClient billingApiClient;
    private final LectureHpReconciliationProperties properties;
    private final Clock clock;
    // 빈(싱글턴)이 가진 실행 중 표시라 배치와 관리자 요청 사이에서 공유된다
    private final AtomicBoolean running = new AtomicBoolean(false);

    public LectureHpReconciliationUseCase(LectureHpReconciliationSupport support,
                                          LectureHpReconciliationMetrics metrics,
                                          BillingApiClient billingApiClient,
                                          LectureHpReconciliationProperties properties,
                                          @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.support = support;
        this.metrics = metrics;
        this.billingApiClient = billingApiClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 매일 배치. 이미 실행 중이면 건너뛴다.
     *
     * @return 끝난 실행. 건너뛰었으면 null
     */
    public LectureHpReconciliationRun runScheduled() {
        if (!running.compareAndSet(false, true)) {
            log.info("강의 HP 대사가 이미 실행 중이라 이번 배치를 건너뛴다");
            return null;
        }
        try {
            return run(LectureHpReconciliationTrigger.SCHEDULED, null);
        } finally {
            running.set(false);
        }
    }

    /**
     * 관리자 수동 실행. 끝날 때까지 기다려 결과를 돌려준다.
     *
     * @throws LectureException 이미 실행 중({@code HP_RECONCILIATION_RUNNING})
     */
    public LectureHpReconciliationRun runManual(Long adminUserId) {
        if (!running.compareAndSet(false, true)) {
            throw new LectureException(LectureErrorCode.HP_RECONCILIATION_RUNNING);
        }
        try {
            return run(LectureHpReconciliationTrigger.MANUAL, adminUserId);
        } finally {
            running.set(false);
        }
    }

    private LectureHpReconciliationRun run(LectureHpReconciliationTrigger trigger, Long adminUserId) {
        LocalDateTime startedAt = LocalDateTime.now(clock);
        Long runId = support.start(trigger, adminUserId, startedAt);
        Progress progress = new Progress();
        String failureReason = null;
        try {
            if (billingApiClient.isCircuitOpen()) {
                throw new WalletUnavailableException("지갑 회로가 열려 있음");
            }
            reconcile(startedAt, progress);
        } catch (WalletUnavailableException exception) {
            failureReason = exception.getMessage();
        } catch (RuntimeException exception) {
            log.error("강의 HP 대사 중 예외로 실행을 멈춘다 runId={}", runId, exception);
            failureReason = "예외: " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
        LectureHpReconciliationRun run = support.finish(runId, progress.mismatches, progress.checked, progress.dead,
                failureReason, progress.cursor, LocalDateTime.now(clock));
        report(run, progress);
        return run;
    }

    private void reconcile(LocalDateTime startedAt, Progress progress) {
        Map<Long, HoldStateDto> listed = readChangedWalletHolds(progress);
        Set<Long> itemIds = new TreeSet<>(support.findChangedItemIds(startedAt.minus(properties.lookback())));
        itemIds.addAll(listed.keySet());
        LocalDateTime settleCutoff = startedAt.minus(properties.settleGrace());
        List<Long> all = List.copyOf(itemIds);
        for (int from = 0; from < all.size(); from += properties.pageSize()) {
            List<Long> chunk = all.subList(from, Math.min(from + properties.pageSize(), all.size()));
            compareChunk(chunk, listed, settleCutoff, progress);
        }
    }

    /** 커서 뒤에 바뀐 지갑 보류를 끝까지 읽는다. 다 읽은 커서는 실행이 완료될 때 저장한다 */
    private Map<Long, HoldStateDto> readChangedWalletHolds(Progress progress) {
        Map<Long, HoldStateDto> listed = new LinkedHashMap<>();
        LectureHpReconciliationSupport.Cursor cursor = support.cursor();
        HoldStatePageDto page;
        do {
            LectureHpReconciliationSupport.Cursor current = cursor;
            page = call("지갑 보류 목록", () -> billingApiClient.listHolds(REFERENCE_TYPE, current.updatedAfter(),
                    current.afterId(), properties.pageSize()));
            for (HoldStateDto item : page.items()) {
                listed.put(item.referenceId(), item);
            }
            cursor = new LectureHpReconciliationSupport.Cursor(page.nextUpdatedAfter(), page.nextAfterId());
        } while (page.hasMore());
        progress.cursor = cursor;
        return listed;
    }

    private void compareChunk(List<Long> chunk, Map<Long, HoldStateDto> listed, LocalDateTime settleCutoff,
                              Progress progress) {
        Map<Long, LectureHpReconciliationTarget> targets = support.loadTargets(chunk);
        List<LectureHpReconciliationTarget> toQuery = new ArrayList<>();
        for (Long itemId : chunk) {
            LectureHpReconciliationTarget target = targets.get(itemId);
            if (target == null && listed.containsKey(itemId)) {
                // 강의에 없는 ID는 지갑 목록에서만 온 것이다
                progress.checked++;
                progress.mismatches.add(new LectureHpReconciliationSupport.Mismatch(itemId, null, null,
                        LectureHpReconciliationComparer.walletOnly(itemId, listed.get(itemId))));
            } else if (target == null) {
                // HP 작업에서 모은 ID인데 항목을 읽지 못했다 (항목은 지우지 않으므로 생기지 않아야 한다)
                log.warn("강의 HP 대사: HP 작업에서 모은 주문 항목을 읽지 못했다 orderItemId={}", itemId);
            } else if (target.isInProgress(settleCutoff)) {
                log.debug("강의 HP 대사: 진행 중이라 뺀다 orderItemId={}", itemId);
            } else if (target.hasDeadTask()) {
                progress.checked++;
                progress.dead++;
                progress.mismatches.add(LectureHpReconciliationSupport.Mismatch.of(target,
                        LectureHpReconciliationComparer.dead(target)));
            } else {
                toQuery.add(target);
            }
        }
        if (toQuery.isEmpty()) {
            return;
        }
        List<Long> queryIds = toQuery.stream().map(LectureHpReconciliationTarget::orderItemId).toList();
        Map<Long, HoldStateDto> states = call("지갑 보류 조회",
                () -> billingApiClient.queryHolds(new HoldQueryCommand(REFERENCE_TYPE, queryIds))).stream()
                .collect(Collectors.toMap(HoldStateDto::referenceId, Function.identity(), (first, second) -> first));
        Map<Long, List<LectureHpReconciliationComparer.Finding>> findings = new LinkedHashMap<>();
        for (LectureHpReconciliationTarget target : toQuery) {
            HoldStateDto state = states.get(target.orderItemId());
            if (state == null) {
                throw new WalletUnavailableException("지갑 보류 조회 응답에 주문 항목 " + target.orderItemId() + "이 없음");
            }
            findings.put(target.orderItemId(), LectureHpReconciliationComparer.compare(target, state));
        }
        Set<Long> changedMeanwhile = findChangedMeanwhile(toQuery, findings);
        for (LectureHpReconciliationTarget target : toQuery) {
            if (changedMeanwhile.contains(target.orderItemId())) {
                log.info("강의 HP 대사: 비교하는 사이에 바뀌어 다음 실행에서 다시 본다 orderItemId={}", target.orderItemId());
                continue;
            }
            progress.checked++;
            for (LectureHpReconciliationComparer.Finding finding : findings.get(target.orderItemId())) {
                progress.mismatches.add(LectureHpReconciliationSupport.Mismatch.of(target, finding));
            }
        }
    }

    /**
     * 어긋남이 나온 항목을 다시 읽어, 강의 쪽을 읽은 뒤 지갑을 조회하기 전에 바뀐 것을 찾는다.
     * 그사이 구매 취소의 즉시 반환이나 HP 작업 배치가 지갑을 바꾸면 강의 쪽은 옛 값, 지갑은 새 값이라 거짓 어긋남이 생긴다.
     * 항목 버전·상태나 마지막 변경 시각이 달라졌으면 이번에는 남기지 않는다(다음 실행이 다시 본다). 어긋남이 없는 항목은 다시 읽지 않는다.
     */
    private Set<Long> findChangedMeanwhile(List<LectureHpReconciliationTarget> compared,
                                           Map<Long, List<LectureHpReconciliationComparer.Finding>> findings) {
        List<Long> suspicious = compared.stream().map(LectureHpReconciliationTarget::orderItemId)
                .filter(itemId -> !findings.get(itemId).isEmpty()).toList();
        if (suspicious.isEmpty()) {
            return Set.of();
        }
        Map<Long, LectureHpReconciliationTarget> reloaded = support.loadTargets(suspicious);
        Set<Long> changed = new TreeSet<>();
        for (LectureHpReconciliationTarget before : compared) {
            if (!suspicious.contains(before.orderItemId())) {
                continue;
            }
            LectureHpReconciliationTarget now = reloaded.get(before.orderItemId());
            if (now == null || now.changedSince(before)) {
                changed.add(before.orderItemId());
            }
        }
        return changed;
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

    private void report(LectureHpReconciliationRun run, Progress progress) {
        for (LectureHpReconciliationSupport.Mismatch mismatch : progress.mismatches) {
            LectureHpReconciliationComparer.Finding finding = mismatch.finding();
            log.warn("{} rule={} runId={} orderItemId={} orderId={} itemStatus={} lecture=[{}] wallet=[{}]",
                    MISMATCH_LOG_TAG, finding.rule(), run.getId(), mismatch.orderItemId(), mismatch.orderId(),
                    mismatch.itemStatus(), finding.lectureState(), finding.walletState());
        }
        metrics.recordRun(run.getStatus());
        if (run.getStatus() == LectureHpReconciliationStatus.COMPLETED) {
            Map<LectureHpReconciliationRule, Long> counts = new EnumMap<>(LectureHpReconciliationRule.class);
            for (LectureHpReconciliationSupport.Mismatch mismatch : progress.mismatches) {
                counts.merge(mismatch.finding().rule(), 1L, Long::sum);
            }
            metrics.updateMismatches(counts);
            log.info("강의 HP 대사 완료 runId={} checked={} mismatches={} dead={}", run.getId(),
                    run.getCheckedCount(), run.getMismatchCount(), run.getDeadCount());
        } else {
            log.warn("강의 HP 대사를 끝까지 하지 못했다. 다음 실행에서 다시 본다 runId={} checked={} mismatches={} reason={}",
                    run.getId(), run.getCheckedCount(), run.getMismatchCount(), run.getFailureReason());
        }
    }

    /** 실행 하나의 진행 상황 */
    private static final class Progress {

        private final List<LectureHpReconciliationSupport.Mismatch> mismatches = new ArrayList<>();
        private LectureHpReconciliationSupport.Cursor cursor;
        private int checked;
        private int dead;
    }

    /** 지갑 조회를 끝내지 못했다. 실행을 중단하고 다음 실행에서 다시 본다 */
    private static final class WalletUnavailableException extends RuntimeException {

        private WalletUnavailableException(String message) {
            super(message);
        }
    }
}
