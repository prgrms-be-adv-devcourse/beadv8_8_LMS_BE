package com.hapbang.appearance.app;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기동할 때 서비스 간 HP 대사 지표를 DB 기록으로 채운다 (#821). 채우는 방법은 강의 #805와 같고, 남은 실행을 정리하지 않는 점이 다르다.
 * <p>
 * 지표는 앱 메모리에 있어, 새벽 대사에서 어긋난 건이 나와도 그날 앱이 다시 뜨면 다음 대사 전까지 0이 된다. 그러면 Grafana alert
 * "서비스 간 HP 대사 불일치"가 고치지 않았는데도 복구 통보를 보내고 조용해진다. 그래서 DB의 마지막 완료 실행의 규칙별 건수와 끝난 시각으로
 * 채운다. 완료한 실행이 없으면 그대로 둔다.
 * <p>
 * DB를 읽기만 한다. 실행 중으로 남은 실행은 여기서 정리하지 않는다. 출연 앱은 배포 중 옛 pod와 새 pod가 잠깐 함께 떠 있어(maxSurge 1)
 * 기동할 때 실행 중인 실행이 옛 pod가 하고 있는 것일 수 있다. 정리는 다음 실행이 오래된 것만 한다({@link HpReconciliationUseCase}).
 * DB를 읽지 못해도 기동은 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HpReconciliationMetricsRestorer {

    private final HpReconciliationSupport hpReconciliationSupport;
    private final HpReconciliationMetrics hpReconciliationMetrics;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        restore();
    }

    /** 마지막 완료 실행의 규칙별 건수와 끝난 시각으로 지표를 바꾼다 */
    public void restore() {
        try {
            hpReconciliationSupport.findLatestCompletedRun().ifPresent(run -> {
                hpReconciliationMetrics.updateMismatches(run.rules());
                hpReconciliationMetrics.updateLastCompleted(run.finishedAt());
                log.info("HP 대사 지표를 마지막 완료 실행으로 채웠다 runId={}, finishedAt={}, mismatches={}", run.runId(),
                        run.finishedAt(), run.rules().size());
            });
        } catch (RuntimeException exception) {
            log.warn("HP 대사 지표를 DB에서 채우지 못해 다음 대사까지 0으로 낸다", exception);
        }
    }
}
