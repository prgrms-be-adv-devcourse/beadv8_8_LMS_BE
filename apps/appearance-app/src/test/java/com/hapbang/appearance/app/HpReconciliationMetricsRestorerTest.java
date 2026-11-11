package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.QueryTimeoutException;

import com.hapbang.appearance.domain.HpReconciliationRule;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 기동할 때 대사 지표를 채우다 DB를 읽지 못해도 기동을 막지 않는지 (#821).
 * DB에서 실제로 복원하는 경로는 AppearanceHpReconciliationTest가 맡는다.
 */
class HpReconciliationMetricsRestorerTest {

    private final HpReconciliationSupport support = mock(HpReconciliationSupport.class);
    /** 레지스트리가 없으면 지표 클래스가 메모리 레지스트리를 쓴다 */
    private final HpReconciliationMetrics metrics = new HpReconciliationMetrics(
            new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
    private final HpReconciliationMetricsRestorer restorer = new HpReconciliationMetricsRestorer(support, metrics);

    @Test
    @DisplayName("마지막 완료 실행을 읽지 못하면 예외를 던지지 않고 지표를 0으로 둔다")
    void dbFailureDoesNotBlockStartup() {
        when(support.findLatestCompletedRun()).thenThrow(new QueryTimeoutException("시험용 DB 오류"));

        assertThatCode(restorer::onApplicationReady).doesNotThrowAnyException();

        assertThat(metrics.mismatches(HpReconciliationRule.HOLD_MISSING)).isZero();
        assertThat(metrics.lastCompletedEpochSeconds()).isZero();
    }
}
