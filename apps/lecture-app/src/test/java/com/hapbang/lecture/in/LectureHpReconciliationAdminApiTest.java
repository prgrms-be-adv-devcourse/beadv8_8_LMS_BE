package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.lecture.app.LectureHpReconciliationUseCase;
import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.out.BillingCallResult;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.billing.RealBillingTestFixture;

// 관리자의 서비스 간 HP 대사 실행·결과 조회 API를 HTTP부터 DB까지 확인한다 (D74, #580).
// 비교 규칙·제외·커서는 LectureHpReconciliationComparerTest·LectureHpReconciliationTest가 맡는다.
@IntegrationTest
@AutoConfigureMockMvc
class LectureHpReconciliationAdminApiTest {

    private static final String BASE = "/api/v1/lectures/admin/hp-reconciliations";
    private static final long WALLET_ONLY_ID = 987_654L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RealBillingTestFixture billing;

    @Autowired
    private LectureHpReconciliationUseCase lectureHpReconciliationUseCase;

    @BeforeEach
    void setUp() {
        billing.reset();
        billing.wallet(OTHER_USER_ID, 1_000_000L, 0L);
    }

    @Test
    void 관리자가_수동_실행하면_실행_결과와_어긋난_건을_주고_latest로_다시_본다() throws Exception {
        assertThat(billing.client().hold(new HoldCommand(OTHER_USER_ID,
                BillingReferenceType.LECTURE_ORDER_ITEM, WALLET_ONLY_ID, PRICE_HP)))
                .isInstanceOf(BillingCallResult.Succeeded.class);
        billing.clearCalls();

        mockMvc.perform(post(BASE).with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("HP 대사를 실행했습니다."))
                .andExpect(jsonPath("$.data.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.data.triggeredBy").value(ADMIN_ID))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.checkedCount").value(1))
                .andExpect(jsonPath("$.data.mismatchCount").value(1))
                .andExpect(jsonPath("$.data.deadCount").value(0))
                .andExpect(jsonPath("$.data.failureReason").doesNotExist())
                .andExpect(jsonPath("$.data.mismatches[0].orderItemId").value(WALLET_ONLY_ID))
                .andExpect(jsonPath("$.data.mismatches[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.data.mismatches[0].rule").value("WALLET_ONLY_HOLD"))
                .andExpect(jsonPath("$.data.mismatches[0].walletState").exists());

        mockMvc.perform(get(BASE + "/latest").with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("마지막 HP 대사 결과를 조회했습니다."))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.mismatches[0].rule").value("WALLET_ONLY_HOLD"));
    }

    @Test
    void 지갑_조회가_실패해도_200이고_INCOMPLETE와_이유를_준다() throws Exception {
        // GET의 내부 재전송도 실제 응답을 받지 못하게 해 조회 실패를 유지한다.
        billing.dropResponses(100);

        mockMvc.perform(post(BASE).with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.data.failureReason").value("지갑 보류 목록 결과를 받지 못함(UNKNOWN_RESULT)"));
    }

    @Test
    void 실행_기록이_없으면_latest는_404_HP_RECONCILIATION_NOT_FOUND다() throws Exception {
        mockMvc.perform(get(BASE + "/latest").with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HP_RECONCILIATION_NOT_FOUND"));
    }

    @Test
    void 실행_중이면_수동_실행은_409_HP_RECONCILIATION_RUNNING이고_latest는_RUNNING이다() throws Exception {
        AtomicBoolean once = new AtomicBoolean();
        billing.duringNextCall(() -> {
            if (once.compareAndSet(false, true)) {
                try {
                    mockMvc.perform(post(BASE).with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                            .andExpect(status().isConflict())
                            .andExpect(jsonPath("$.code").value("HP_RECONCILIATION_RUNNING"));
                    mockMvc.perform(get(BASE + "/latest").with(GatewayLogin.as(ADMIN_ID, Role.ADMIN)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.status").value("RUNNING"))
                            .andExpect(jsonPath("$.data.mismatches").isEmpty());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        lectureHpReconciliationUseCase.runScheduled();

        assertThat(once).isTrue();
    }

    @Test
    void 관리자가_아니면_403이다() throws Exception {
        mockMvc.perform(post(BASE).with(GatewayLogin.as(SELLER_ID, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/latest").with(GatewayLogin.as(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isForbidden());
    }
}
