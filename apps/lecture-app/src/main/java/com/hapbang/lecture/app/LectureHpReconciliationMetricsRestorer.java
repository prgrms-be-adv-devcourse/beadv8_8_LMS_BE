package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpReconciliationStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * 기동할 때 서비스 간 HP 대사 지표를 DB 기록으로 맞춘다 (#805).
 * <ul>
 *     <li>지표는 앱 메모리에 있어, 새벽 대사에서 어긋난 건이 나와도 그날 앱이 다시 뜨면 다음 대사 전까지 0이 된다. 그러면 Grafana alert
 *     "서비스 간 HP 대사 불일치"가 고치지 않았는데도 복구 통보를 보내고 조용해진다. 그래서 DB의 마지막 완료 실행으로 채운다.
 *     완료한 실행이 없으면 그대로 둔다</li>
 *     <li>앱이 대사 도중 멈춰 실행 중으로 남은 실행은 미완료로 끝내고 중단 횟수를 올린다. 그러지 않으면 다음 실행 때 조용히 정리돼
 *     alert "대사 중단"이 알지 못한다</li>
 * </ul>
 * DB를 읽지 못해도 기동은 막지 않는다.
 */
@Slf4j
@Component
public class LectureHpReconciliationMetricsRestorer {

    private final LectureHpReconciliationSupport support;
    private final LectureHpReconciliationMetrics metrics;
    private final Clock clock;
    /** 이 앱이 뜨기 시작한 시각. 이보다 먼저 시작해 실행 중으로 남은 실행은 앞 pod가 하던 것이다 */
    private final LocalDateTime bootedAt;

    public LectureHpReconciliationMetricsRestorer(LectureHpReconciliationSupport support,
                                                  LectureHpReconciliationMetrics metrics,
                                                  @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.support = support;
        this.metrics = metrics;
        this.clock = clock;
        this.bootedAt = LocalDateTime.now(clock);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        restore();
    }

    /** 앞 pod가 남긴 실행 중 실행을 정리하고, 마지막 완료 실행의 규칙별 건수와 끝난 시각으로 지표를 바꾼다 */
    public void restore() {
        try {
            List<Long> abandonedIds = support.abandonRunningStartedBefore(bootedAt, LocalDateTime.now(clock));
            if (!abandonedIds.isEmpty()) {
                abandonedIds.forEach(id -> metrics.recordRun(LectureHpReconciliationStatus.INCOMPLETE));
                log.warn("앱이 멈춰 끝나지 못한 강의 HP 대사를 미완료로 정리했다 runIds={}", abandonedIds);
            }
            support.findLatestCompletedRun().ifPresent(run -> {
                metrics.updateMismatches(run.counts());
                metrics.updateLastCompleted(run.finishedAt());
                log.info("강의 HP 대사 지표를 마지막 완료 실행으로 채웠다 runId={}, finishedAt={}, counts={}", run.runId(),
                        run.finishedAt(), run.counts());
            });
        } catch (RuntimeException exception) {
            log.warn("강의 HP 대사 지표를 DB에서 채우지 못해 다음 대사까지 0으로 낸다", exception);
        }
    }
}
