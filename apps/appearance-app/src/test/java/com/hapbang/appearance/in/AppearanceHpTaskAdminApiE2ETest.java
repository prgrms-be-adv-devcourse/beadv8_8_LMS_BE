package com.hapbang.appearance.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.appearance.app.HpTaskExecuteUseCase;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.TestLogin;

/**
 * 관리자 HP 작업 API (#397). 앱 전체(보안 필터, 세션 로그인, CSRF, 메서드 보안, DB, Billing 대역)를 띄우고 HTTP로 부른다.
 * 격리된 작업은 기록 → 실행 → Billing 거절로 만든다.
 */
@AppearanceIntegrationTest
@AutoConfigureMockMvc
class AppearanceHpTaskAdminApiE2ETest {

    private static final String BASE = "/api/v1/appearances/admin/hp-tasks";
    private static final long ADMIN = 900L;
    private static final long INFLUENCER = 1L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @Autowired
    BillingClient billingClient;

    @Autowired
    AppearanceTestClock clock;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.user(ADMIN, Role.ADMIN, UserStatus.ACTIVE));
    }

    private long deadTask() {
        BillingStubs.reject(billingClient, Rejection.HOLD_NOT_FOUND);
        HpTask task = hpTaskRepository.saveAndFlush(HpTask.release(HpTaskRefType.APPEARANCE_REQUEST, 11L,
                INFLUENCER, 30_000L, HpTaskReason.REQUEST_REJECTED,
                HpTask.requestId(HpTaskRefType.APPEARANCE_REQUEST, 11L, HpTaskType.RELEASE, 1), clock.now()));
        hpTaskExecuteUseCase.execute(task.getId());
        BillingStubs.available(billingClient);
        return task.getId();
    }

    @Test
    @DisplayName("관리자: 목록(기본 DEAD) 200 → 다시 시도 200 DONE·다시 시도 기록 → 한 번 더 409 HP_TASK_NOT_DEAD → 목록 비어 있음")
    void adminFlow() throws Exception {
        long taskId = deadTask();

        mockMvc.perform(get(BASE).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("HP 작업 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.content[0].id").value(taskId))
                .andExpect(jsonPath("$.data.content[0].status").value("DEAD"))
                .andExpect(jsonPath("$.data.content[0].taskType").value("RELEASE"))
                .andExpect(jsonPath("$.data.content[0].lastError").value("HOLD_NOT_FOUND: HP 보류를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.data.content[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.page.totalElements").value(1));

        mockMvc.perform(post(BASE + "/" + taskId + "/retry").with(TestLogin.as(ADMIN, Role.ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("HP 작업을 다시 시도했습니다."))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.manualRetryCount").value(1))
                .andExpect(jsonPath("$.data.lastManualRetryBy").value(ADMIN));

        mockMvc.perform(post(BASE + "/" + taskId + "/retry").with(TestLogin.as(ADMIN, Role.ADMIN)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HP_TASK_NOT_DEAD"));

        mockMvc.perform(get(BASE + "?status=DEAD").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    @DisplayName("없는 작업 404 HP_TASK_NOT_FOUND, 없는 상태 값 400")
    void notFoundAndBadStatus() throws Exception {
        mockMvc.perform(post(BASE + "/999999/retry").with(TestLogin.as(ADMIN, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HP_TASK_NOT_FOUND"));
        mockMvc.perform(get(BASE + "?status=UNKNOWN").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("세션은 관리자지만 관리자 권한을 잃었으면(복제본 USER) 목록·다시 시도 403 NOT_ADMIN")
    void demotedAdmin() throws Exception {
        long taskId = deadTask();
        appearanceUserFacade.syncModified(AppearanceUserFixture.user(ADMIN, Role.USER, UserStatus.ACTIVE));

        mockMvc.perform(post(BASE + "/" + taskId + "/retry").with(TestLogin.as(ADMIN, Role.ADMIN)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMIN"));
        mockMvc.perform(get(BASE).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMIN"));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 비로그인 401. 작업은 그대로 DEAD")
    void adminOnly() throws Exception {
        long taskId = deadTask();

        mockMvc.perform(get(BASE).with(TestLogin.as(INFLUENCER, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/" + taskId + "/retry")
                        .with(TestLogin.as(INFLUENCER, Role.INFLUENCER)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(BASE).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(jsonPath("$.data.content[0].status").value("DEAD"))
                .andExpect(jsonPath("$.data.content[0].manualRetryCount").value(0));
    }
}
