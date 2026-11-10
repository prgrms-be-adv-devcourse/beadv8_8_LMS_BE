package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.appearance.domain.HpReconciliationRun;
import com.hapbang.appearance.domain.HpReconciliationStatus;
import com.hapbang.appearance.out.HpReconciliationRunRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
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

        HpReconciliationRun failed = hpReconciliationUseCase.runScheduled();

        assertThat(failed.getStatus()).isEqualTo(HpReconciliationStatus.INCOMPLETE);
        assertThat(failed.getFailureReason())
                .isEqualTo(HpReconciliationUseCase.FINISH_FAILED_REASON + "QueryTimeoutException");
        assertThat(hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE)).isEqualTo(incompleteBefore + 1);

        doCallRealFinish();
        HpReconciliationRun next = hpReconciliationUseCase.runScheduled();

        assertThat(next.getStatus()).isEqualTo(HpReconciliationStatus.COMPLETED);
        assertThat(runRepository.findAll()).extracting(HpReconciliationRun::getStatus)
                .containsExactlyInAnyOrder(HpReconciliationStatus.INCOMPLETE, HpReconciliationStatus.COMPLETED);
    }

    private void doCallRealFinish() {
        doCallRealMethod().when(hpReconciliationSupport)
                .finish(any(), anyList(), anyInt(), anyInt(), any(), anyMap(), any());
    }
}
