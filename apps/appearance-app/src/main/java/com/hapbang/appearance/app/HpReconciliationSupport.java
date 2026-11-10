package com.hapbang.appearance.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
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

    /** 지갑 보류 목록 커서. 저장한 적이 없으면 처음부터 ({@link Instant#EPOCH}, 0) */
    record Cursor(Instant updatedAfter, long afterId) {

        static final Cursor START = new Cursor(Instant.EPOCH, 0);
    }

    /**
     * 앱이 대사 도중 멈춰 실행 중으로 남은 실행을 {@code INCOMPLETE}로 끝낸다. 실행을 시작하기 전에 부른다.
     * 배포 중에는 옛 pod와 새 pod가 잠깐 함께 떠 있어(maxSurge 1) 실행 중인 실행이 옛 pod가 아직 하고 있는 것일 수 있다.
     * 그래서 {@code startedBefore} 전에 시작한 것만 끝낸다(#821).
     *
     * @return 끝낸 실행 ID
     */
    @Transactional
    public List<Long> abandonRunningStartedBefore(LocalDateTime startedBefore, LocalDateTime now) {
        List<Long> abandonedIds = new ArrayList<>();
        for (HpReconciliationRun run : runRepository.findByStatus(HpReconciliationStatus.RUNNING)) {
            if (run.getStartedAt().isBefore(startedBefore)) {
                run.incomplete(run.getCheckedCount(), run.getMismatchCount(), run.getDeadCount(), ABANDONED_REASON,
                        now);
                abandonedIds.add(run.getId());
            }
        }
        return abandonedIds;
    }

    /**
     * 실행을 시작한다.
     *
     * @return 새 실행 ID
     */
    @Transactional
    public Long start(HpReconciliationTrigger trigger, Long adminId, LocalDateTime now) {
        return runRepository.save(HpReconciliationRun.start(trigger, adminId, now)).getId();
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
    public HpReconciliationRun finish(Long runId, List<Mismatch> mismatches, int checkedCount, int deadCount,
                                      String failureReason, Map<HpTaskRefType, Cursor> cursors, LocalDateTime now) {
        HpReconciliationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("대사 실행이 없습니다: " + runId));
        for (Mismatch mismatch : mismatches) {
            HpReconciliationComparer.Finding finding = mismatch.finding();
            mismatchRepository.save(new HpReconciliationMismatch(runId, mismatch.refType(), mismatch.refId(),
                    finding.appearanceId(), finding.rule(), finding.appearanceState(), finding.walletState()));
        }
        int mismatchCount = mismatches.size() - deadCount;
        if (failureReason != null) {
            run.incomplete(checkedCount, mismatchCount, deadCount, failureReason, now);
            return run;
        }
        run.complete(checkedCount, mismatchCount, deadCount, now);
        cursors.forEach((refType, cursor) -> {
            LocalDateTime updatedAfter = LocalDateTime.ofInstant(cursor.updatedAfter(), AppearanceTime.ZONE);
            cursorRepository.findById(refType).ifPresentOrElse(
                    saved -> saved.moveTo(updatedAfter, cursor.afterId()),
                    () -> cursorRepository.save(new HpReconciliationCursor(refType, updatedAfter, cursor.afterId())));
        });
        return run;
    }

    @Transactional(readOnly = true)
    public HpReconciliationRun findLatestRun() {
        return runRepository.findFirstByOrderByStartedAtDescIdDesc().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<HpReconciliationMismatch> findMismatches(Long runId) {
        return mismatchRepository.findByRunIdOrderByIdAsc(runId);
    }

    /** 마지막으로 끝까지 비교한({@code COMPLETED}) 실행. 기동할 때 지표를 채우는 데 쓴다 (#821) */
    @Transactional(readOnly = true)
    public Optional<CompletedRun> findLatestCompletedRun() {
        return runRepository.findFirstByStatusOrderByFinishedAtDescIdDesc(HpReconciliationStatus.COMPLETED)
                .map(run -> {
                    Map<HpReconciliationRule, Long> counts = new EnumMap<>(HpReconciliationRule.class);
                    for (HpReconciliationMismatch mismatch : mismatchRepository.findByRunIdOrderByIdAsc(run.getId())) {
                        counts.merge(mismatch.getRule(), 1L, Long::sum);
                    }
                    return new CompletedRun(run.getId(), run.getFinishedAt(), counts);
                });
    }

    /** 끝까지 비교한 실행 하나의 지표 값: 끝난 시각과 규칙별 건수({@code HP_TASK_DEAD} 표시 포함) */
    record CompletedRun(Long runId, LocalDateTime finishedAt, Map<HpReconciliationRule, Long> counts) {
    }

    /** 남길 어긋남 하나: 대상 + 비교 결과 */
    record Mismatch(HpTaskRefType refType, Long refId, HpReconciliationComparer.Finding finding) {
    }
}
