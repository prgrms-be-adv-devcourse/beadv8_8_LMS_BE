package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.appearance.app.dto.HpReconciliationRunResDto;
import com.hapbang.appearance.domain.HpReconciliationRun;
import com.hapbang.appearance.domain.HpReconciliationStatus;
import com.hapbang.appearance.out.HpReconciliationRunRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.shared.billing.dto.HoldStatePageDto;
import com.hapbang.shared.billing.out.BillingCallResult;

/**
 * 대사 결과 기록({@code finish})이 실패할 때 (#821). 실행이 {@code RUNNING}으로 남으면 {@code abandon-after} 동안 다음 실행이 막히므로
 * 바로 미완료로 끝내고 중단 횟수를 올린다. 기록 실패는 두 pod의 커서 버전 충돌이나 DB 순간 장애에서 나며, 여기서는 Support 대역으로 만든다.
 */
@AppearanceIntegrationTest
class AppearanceHpReconciliationFinishFailureTest {

    @MockitoSpyBean
    HpReconciliationSupport hpReconciliationSupport;

    @Autowired
    HpReconciliationUseCase hpReconciliationUseCase;

    @Autowired
    HpReconciliationMetrics hpReconciliationMetrics;

    @Autowired
    HpReconciliationRunRepository runRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    AppearanceTestClock clock;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** 지갑에 바뀐 보류가 없다 */
    @BeforeEach
    void setUp() {
        doReturn(new BillingCallResult.Succeeded<>(new HoldStatePageDto(List.of(), Instant.EPOCH, 0, false)))
                .when(billingClient).listHolds(any(), any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("결과 기록이 실패하면 실행을 INCOMPLETE(결과 기록 실패: 예외 이름)로 끝내고 중단 횟수를 올린다. 바로 다시 실행할 수 있다")
    void finishFailureAbandonsRun() {
        double incompleteBefore = hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE);
        doThrow(new QueryTimeoutException("시험용 DB 오류")).when(hpReconciliationSupport)
                .finish(any(), anyList(), anyInt(), anyInt(), any(), anyMap(), any());

        HpReconciliationRunResDto failed = hpReconciliationUseCase.runScheduled();

        assertThat(failed.status()).isEqualTo(HpReconciliationStatus.INCOMPLETE);
        assertThat(failed.failureReason())
                .isEqualTo(HpReconciliationUseCase.FINISH_FAILED_REASON + "QueryTimeoutException");
        assertThat(hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE)).isEqualTo(incompleteBefore + 1);

        doCallRealFinish();
        HpReconciliationRunResDto next = hpReconciliationUseCase.runScheduled();

        assertThat(next.status()).isEqualTo(HpReconciliationStatus.COMPLETED);
        assertThat(runRepository.findAll()).extracting(HpReconciliationRun::getStatus)
                .containsExactlyInAnyOrder(HpReconciliationStatus.INCOMPLETE, HpReconciliationStatus.COMPLETED);
    }

    @Test
    @DisplayName("기록하기 전에 다른 pod가 이 실행을 먼저 정리했으면(1시간 넘긴 실행) 그 상태 그대로 두고 중단 횟수를 다시 올리지 않는다")
    void closedByOtherPodIsNotCountedTwice() {
        double incompleteBefore = hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE);
        doAnswer(invocation -> {
            // 다른 pod의 실행 시작 정리. 그 pod는 자기 쪽에서 중단 횟수를 올린다(여기 지표에는 오르지 않음).
            // finish 트랜잭션 안에서 불리므로 따로 커밋해야 finish가 롤백돼도 남는다
            TransactionTemplate otherPod = new TransactionTemplate(transactionManager);
            otherPod.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            otherPod.executeWithoutResult(status -> jdbcTemplate.update("update appearances.hp_reconciliation_runs set status = 'INCOMPLETE', failure_reason = ?, "
                    + "finished_at = ?, version = version + 1 where status = 'RUNNING'",
                    HpReconciliationSupport.ABANDONED_REASON, clock.now()));
            return invocation.callRealMethod();
        }).when(hpReconciliationSupport).finish(any(), anyList(), anyInt(), anyInt(), any(), anyMap(), any());

        HpReconciliationRunResDto run = hpReconciliationUseCase.runScheduled();

        assertThat(run.status()).isEqualTo(HpReconciliationStatus.INCOMPLETE);
        assertThat(run.failureReason()).isEqualTo(HpReconciliationSupport.ABANDONED_REASON);
        assertThat(hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE)).isEqualTo(incompleteBefore);
    }

    @Test
    @DisplayName("미완료 처리마저 실패하면 예외를 던지고 RUNNING이 남는다. 실행 중 표시는 풀리고, 1시간 뒤 다음 실행이 정리하고 완료한다")
    void abandonFailureIsCleanedAfterAbandonAfter() {
        double incompleteBefore = hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE);
        doThrow(new QueryTimeoutException("시험용 DB 오류")).when(hpReconciliationSupport)
                .finish(any(), anyList(), anyInt(), anyInt(), any(), anyMap(), any());
        doThrow(new QueryTimeoutException("시험용 DB 오류")).when(hpReconciliationSupport)
                .abandonIfRunning(any(), any(), any());

        assertThatThrownBy(() -> hpReconciliationUseCase.runScheduled()).isInstanceOf(QueryTimeoutException.class);
        assertThat(runRepository.findAll()).extracting(HpReconciliationRun::getStatus)
                .containsExactly(HpReconciliationStatus.RUNNING);
        // 1시간 안에는 남은 실행 때문에 시작하지 않는다(메모리 표시가 풀려 409·건너뜀은 DB 확인에서 난다)
        assertThat(hpReconciliationUseCase.runScheduled()).isNull();

        doCallRealFinish();
        doCallRealMethod().when(hpReconciliationSupport).abandonIfRunning(any(), any(), any());
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        HpReconciliationRunResDto next = hpReconciliationUseCase.runScheduled();

        assertThat(next.status()).isEqualTo(HpReconciliationStatus.COMPLETED);
        assertThat(runRepository.findAll()).extracting(HpReconciliationRun::getStatus, HpReconciliationRun::getFailureReason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(HpReconciliationStatus.INCOMPLETE,
                                HpReconciliationSupport.ABANDONED_REASON),
                        org.assertj.core.groups.Tuple.tuple(HpReconciliationStatus.COMPLETED, null));
        assertThat(hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE)).isEqualTo(incompleteBefore + 1);
    }

    private void doCallRealFinish() {
        doCallRealMethod().when(hpReconciliationSupport)
                .finish(any(), anyList(), anyInt(), anyInt(), any(), anyMap(), any());
    }
}
