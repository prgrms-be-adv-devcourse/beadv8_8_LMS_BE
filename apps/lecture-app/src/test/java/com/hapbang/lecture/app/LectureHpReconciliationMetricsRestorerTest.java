package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.QueryTimeoutException;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureHpReconciliationRule;
import com.hapbang.lecture.domain.LectureHpReconciliationStatus;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 기동할 때 대사 지표를 채우는 두 단계(남은 실행 정리, 마지막 완료 실행 복원)가 서로의 실패에 막히지 않는지 (#805).
 * DB에서 실제로 정리·복원하는 경로는 LectureHpReconciliationTest가 맡는다.
 */
class LectureHpReconciliationMetricsRestorerTest {

    private static final LocalDateTime FINISHED_AT = LocalDateTime.of(2026, 11, 9, 4, 31, 2);

    private final LectureHpReconciliationSupport support = mock(LectureHpReconciliationSupport.class);
    /** 레지스트리가 없으면 지표 클래스가 메모리 레지스트리를 쓴다 */
    private final LectureHpReconciliationMetrics metrics = new LectureHpReconciliationMetrics(
            new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
    private final LectureHpReconciliationMetricsRestorer restorer = new LectureHpReconciliationMetricsRestorer(
            support, metrics, Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), LectureConfig.ZONE));

    @Test
    void 남은_실행을_정리하지_못해도_마지막_완료_실행으로_지표를_채운다() {
        when(support.abandonRunningStartedBefore(any(), any())).thenThrow(new QueryTimeoutException("시험용 DB 오류"));
        when(support.findLatestCompletedRun()).thenReturn(Optional.of(new LectureHpReconciliationSupport.CompletedRun(
                7L, FINISHED_AT, Map.of(LectureHpReconciliationRule.HOLD_MISMATCH, 2L))));

        restorer.restore();

        assertThat(metrics.mismatches(LectureHpReconciliationRule.HOLD_MISMATCH)).isEqualTo(2);
        assertThat(metrics.lastCompletedEpochSeconds())
                .isEqualTo(FINISHED_AT.atZone(LectureConfig.ZONE).toEpochSecond());
    }

    @Test
    void 마지막_완료_실행을_읽지_못해도_남은_실행은_정리하고_중단_횟수를_올린다() {
        when(support.abandonRunningStartedBefore(any(), any())).thenReturn(List.of(3L));
        when(support.findLatestCompletedRun()).thenThrow(new QueryTimeoutException("시험용 DB 오류"));

        restorer.restore();

        assertThat(metrics.runs(LectureHpReconciliationStatus.INCOMPLETE)).isEqualTo(1);
        assertThat(metrics.lastCompletedEpochSeconds()).isZero();
    }
}
