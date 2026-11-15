package com.hapbang.appearance.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.HpReconciliationRunResDto;
import com.hapbang.appearance.config.AppearanceHpReconciliationProperties;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.HpReconciliationCursor;
import com.hapbang.appearance.domain.HpReconciliationMismatch;
import com.hapbang.appearance.domain.HpReconciliationRule;
import com.hapbang.appearance.domain.HpReconciliationRun;
import com.hapbang.appearance.domain.HpReconciliationStatus;
import com.hapbang.appearance.domain.HpReconciliationTrigger;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.AppearanceRepository;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.out.HpReconciliationCursorRepository;
import com.hapbang.appearance.out.HpReconciliationMismatchRepository;
import com.hapbang.appearance.out.HpReconciliationRunRepository;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.shared.time.AppearanceTime;

import lombok.RequiredArgsConstructor;

/**
 * 서비스 간 HP 대사의 DB 읽기·기록 (#581). 메서드마다 트랜잭션을 연다. 지갑 호출은 이 클래스 밖(트랜잭션 밖)에서 한다.
 * <p>
 * 공개 메서드는 엔티티를 돌려주지 않는다. 실행은 트랜잭션 안에서 어긋난 건까지 담은 응답 record({@link HpReconciliationRunResDto})로
 * 만들어 돌려준다 (#911). 받는 쪽은 트랜잭션 밖이라 엔티티를 고쳐도 저장되지 않고 오류도 나지 않기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class HpReconciliationSupport {

    /** 앱이 멈춰 실행 중으로 남은 실행을 정리할 때 남기는 사유 */
    static final String ABANDONED_REASON = "앱이 멈춰 끝나지 못한 실행";

    private final HpReconciliationRunRepository runRepository;
    private final HpReconciliationMismatchRepository mismatchRepository;
    private final HpReconciliationCursorRepository cursorRepository;
    private final AppearanceRequestRepository appearanceRequestRepository;
    private final AppearanceRecruitRepository appearanceRecruitRepository;
    private final AppearanceRepository appearanceRepository;
    private final HpTaskRepository hpTaskRepository;
    private final AppearanceHpReconciliationProperties properties;

    /** 지갑 보류 목록 커서. 저장한 적이 없으면 처음부터 ({@link Instant#EPOCH}, 0) */
    record Cursor(Instant updatedAfter, long afterId) {

        static final Cursor START = new Cursor(Instant.EPOCH, 0);
    }

    /**
     * 실행을 시작한다. 다른 실행이 실행 중이면 시작하지 않는다 (#911).
     * <ol>
     *     <li>시작 구간 잠금(트랜잭션 advisory lock)을 잡는다. 다른 pod가 이 구간에 있으면 그 트랜잭션이 끝날 때까지 기다린다</li>
     *     <li>앱이 대사 도중 멈춰 실행 중으로 남은 실행을 {@code INCOMPLETE}로 끝낸다. 배포 중에는 옛 pod와 새 pod가 잠깐 함께 떠 있어
     *     (maxSurge 1) 실행 중인 실행이 옛 pod가 아직 하고 있는 것일 수 있으므로 {@code abandon-after}보다 오래된 것만 끝낸다(#821)</li>
     *     <li>그래도 실행 중인 실행이 남아 있으면 다른 pod가 하고 있는 것이다. 시작하지 않는다</li>
     *     <li>없으면 새 실행을 {@code RUNNING}으로 저장한다</li>
     * </ol>
     * 세 단계를 한 트랜잭션에서 잠그고 하므로 두 pod가 같은 순간에 시작해도 {@code RUNNING} 실행은 하나만 생긴다. 기다린 쪽은 잠금을 잡은 뒤
     * 먼저 시작한 실행을 읽는다(READ COMMITTED는 문장마다 새로 읽는다). 대사 전체는 잠그지 않는다. 지갑 API를 여러 번 부르는 동안
     * 트랜잭션과 DB 연결을 쥐고 있게 되기 때문이다.
     */
    @Transactional
    public StartResult start(HpReconciliationTrigger trigger, Long adminId, LocalDateTime now) {
        runRepository.lockStart();
        LocalDateTime startedBefore = now.minus(properties.abandonAfter());
        List<Long> abandonedIds = new ArrayList<>();
        List<HpReconciliationRunResDto> runningRuns = new ArrayList<>();
        for (HpReconciliationRun run : runRepository.findByStatus(HpReconciliationStatus.RUNNING)) {
            if (run.getStartedAt().isBefore(startedBefore)) {
                run.abandon(ABANDONED_REASON, now);
                abandonedIds.add(run.getId());
            } else {
                // 실행 중인 실행에는 어긋난 건이 아직 없다(끝날 때 남긴다)
                runningRuns.add(HpReconciliationRunResDto.from(run, List.of()));
            }
        }
        if (!runningRuns.isEmpty()) {
            return new StartResult.Blocked(abandonedIds, runningRuns);
        }
        Long runId = runRepository.save(HpReconciliationRun.start(trigger, adminId, now)).getId();
        return new StartResult.Started(runId, abandonedIds);
    }

    @Transactional(readOnly = true)
    public Cursor cursorOf(HpTaskRefType refType) {
        return cursorRepository.findById(refType)
                .map(cursor -> new Cursor(cursor.getUpdatedAfter().atZone(AppearanceTime.ZONE).toInstant(),
                        cursor.getAfterId()))
                .orElse(Cursor.START);
    }

    /** 이 시각 이후 대상·출연·HP 작업 중 하나라도 바뀐 요청서·모집글 ID */
    @Transactional(readOnly = true)
    public Set<Long> findChangedRefIds(HpTaskRefType refType, LocalDateTime since) {
        Set<Long> ids = new TreeSet<>(hpTaskRepository.findRefIdsUpdatedSince(refType, since));
        if (refType == HpTaskRefType.APPEARANCE_REQUEST) {
            ids.addAll(appearanceRequestRepository.findIdsUpdatedSince(since));
            ids.addAll(appearanceRepository.findRequestIdsUpdatedSince(since));
        } else {
            ids.addAll(appearanceRecruitRepository.findIdsUpdatedSince(since));
            ids.addAll(appearanceRepository.findRecruitIdsUpdatedSince(since));
        }
        return ids;
    }

    /**
     * 요청서·모집글과 그 출연·HP 작업을 읽어 비교 대상으로 만든다.
     *
     * @return 요청서·모집글 ID → 비교 대상. 출연에 없는 ID는 빠진다(지갑에만 있는 보류)
     */
    @Transactional(readOnly = true)
    public Map<Long, HpReconciliationTarget> loadTargets(HpTaskRefType refType, Collection<Long> refIds) {
        Map<Long, List<HpTask>> tasksByRef = hpTaskRepository.findByRefTypeAndRefIdIn(refType, refIds).stream()
                .collect(Collectors.groupingBy(HpTask::getRefId));
        Map<Long, HpReconciliationTarget> targets = new LinkedHashMap<>();
        if (refType == HpTaskRefType.APPEARANCE_REQUEST) {
            Map<Long, Appearance> appearanceByRequest = appearanceRepository.findByRequestIdIn(refIds).stream()
                    .collect(Collectors.toMap(Appearance::getRequestId, Function.identity()));
            for (AppearanceRequest request : appearanceRequestRepository.findAllById(refIds)) {
                targets.put(request.getId(), HpReconciliationTarget.ofRequest(request,
                        appearanceByRequest.get(request.getId()), tasksByRef.getOrDefault(request.getId(), List.of())));
            }
        } else {
            Map<Long, List<Appearance>> appearancesByRecruit = appearanceRepository.findByRecruitIdIn(refIds).stream()
                    .collect(Collectors.groupingBy(Appearance::getRecruitId));
            for (AppearanceRecruit recruit : appearanceRecruitRepository.findAllById(refIds)) {
                targets.put(recruit.getId(), HpReconciliationTarget.ofRecruit(recruit,
                        appearancesByRecruit.getOrDefault(recruit.getId(), List.of()),
                        tasksByRef.getOrDefault(recruit.getId(), List.of())));
            }
        }
        return targets;
    }

    /**
     * 실행을 끝내고 어긋난 건을 남긴다. 끝까지 비교했으면 커서를 앞으로 옮긴다.
     *
     * @param failureReason 끝까지 비교하지 못한 이유. null이면 {@code COMPLETED}
     * @param cursors       끝까지 읽은 지갑 보류 목록 커서 (완료일 때만 저장)
     */
    @Transactional
    public HpReconciliationRunResDto finish(Long runId, List<Mismatch> mismatches, int checkedCount, int deadCount,
                                            String failureReason, Map<HpTaskRefType, Cursor> cursors,
                                            LocalDateTime now) {
        HpReconciliationRun run = findRunOrThrow(runId);
        List<HpReconciliationMismatch> savedMismatches = new ArrayList<>();
        for (Mismatch mismatch : mismatches) {
            HpReconciliationComparer.Finding finding = mismatch.finding();
            savedMismatches.add(mismatchRepository.save(new HpReconciliationMismatch(runId, mismatch.refType(),
                    mismatch.refId(), finding.appearanceId(), finding.rule(), finding.appearanceState(),
                    finding.walletState())));
        }
        int mismatchCount = mismatches.size() - deadCount;
        if (failureReason != null) {
            run.incomplete(checkedCount, mismatchCount, deadCount, failureReason, now);
            return HpReconciliationRunResDto.from(run, savedMismatches);
        }
        run.complete(checkedCount, mismatchCount, deadCount, now);
        cursors.forEach((refType, cursor) -> {
            LocalDateTime updatedAfter = LocalDateTime.ofInstant(cursor.updatedAfter(), AppearanceTime.ZONE);
            cursorRepository.findById(refType).ifPresentOrElse(
                    saved -> saved.moveTo(updatedAfter, cursor.afterId()),
                    () -> cursorRepository.save(new HpReconciliationCursor(refType, updatedAfter, cursor.afterId())));
        });
        return HpReconciliationRunResDto.from(run, savedMismatches);
    }

    /**
     * 결과를 기록하지 못한 실행({@link #finish} 실패)을 미완료로 끝낸다 (#821). 그대로 두면 {@code RUNNING}으로 남아
     * {@code abandon-after} 동안 다음 실행이 막힌다.
     *
     * @return 이번 호출이 끝냈으면 true. 그새 다른 pod가 먼저 정리했으면(이미 끝난 실행) 그대로 두고 false
     */
    @Transactional
    public boolean abandonIfRunning(Long runId, String reason, LocalDateTime now) {
        HpReconciliationRun run = findRunOrThrow(runId);
        if (!run.isRunning()) {
            return false;
        }
        run.abandon(reason, now);
        return true;
    }

    /** 실행과 그 어긋난 건 */
    @Transactional(readOnly = true)
    public HpReconciliationRunResDto findRun(Long runId) {
        return withMismatches(findRunOrThrow(runId));
    }

    private HpReconciliationRun findRunOrThrow(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("대사 실행이 없습니다: " + runId));
    }

    /** @return 마지막으로 시작한 실행과 그 어긋난 건. 실행 기록이 없으면 null */
    @Transactional(readOnly = true)
    public HpReconciliationRunResDto findLatestRun() {
        Optional<HpReconciliationRun> latest = runRepository.findFirstByOrderByStartedAtDescIdDesc();
        if (latest.isEmpty()) {
            return null;
        }
        return withMismatches(latest.get());
    }

    private HpReconciliationRunResDto withMismatches(HpReconciliationRun run) {
        return HpReconciliationRunResDto.from(run, mismatchRepository.findByRunIdOrderByIdAsc(run.getId()));
    }

    /** 마지막으로 끝까지 비교한({@code COMPLETED}) 실행. 기동할 때 지표를 채우는 데 쓴다 (#821) */
    @Transactional(readOnly = true)
    public Optional<CompletedRun> findLatestCompletedRun() {
        Optional<HpReconciliationRun> latest =
                runRepository.findFirstByStatusOrderByFinishedAtDescIdDesc(HpReconciliationStatus.COMPLETED);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        HpReconciliationRun run = latest.get();
        List<HpReconciliationRule> mismatchRules = new ArrayList<>();
        for (HpReconciliationMismatch mismatch : mismatchRepository.findByRunIdOrderByIdAsc(run.getId())) {
            mismatchRules.add(mismatch.getRule());
        }
        return Optional.of(new CompletedRun(run.getId(), run.getFinishedAt(), mismatchRules));
    }

    /**
     * 끝까지 비교한 실행 하나의 지표 값.
     *
     * @param mismatchRules 어긋난 건마다의 규칙({@code HP_TASK_DEAD} 표시 포함). 같은 규칙이 여러 번 나온다
     */
    record CompletedRun(Long runId, LocalDateTime finishedAt, List<HpReconciliationRule> mismatchRules) {
    }

    /** 실행 시작 결과 (#911). 어느 쪽이든 앱이 멈춰 남아 있어 이번에 {@code INCOMPLETE}로 끝낸 실행 ID를 담는다 */
    sealed interface StartResult {

        List<Long> abandonedIds();

        /** 새 실행을 시작했다 */
        record Started(
                Long runId,
                List<Long> abandonedIds
        ) implements StartResult {
        }

        /** 다른 실행이 실행 중이라 시작하지 않았다 */
        record Blocked(
                List<Long> abandonedIds,
                List<HpReconciliationRunResDto> runningRuns
        ) implements StartResult {
        }
    }

    /** 남길 어긋남 하나: 대상 + 비교 결과 */
    record Mismatch(HpTaskRefType refType, Long refId, HpReconciliationComparer.Finding finding) {
    }
}
