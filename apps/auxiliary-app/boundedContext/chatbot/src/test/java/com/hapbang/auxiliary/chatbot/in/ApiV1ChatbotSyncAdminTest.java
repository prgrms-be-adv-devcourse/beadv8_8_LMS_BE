package com.hapbang.auxiliary.chatbot.in;

import static com.hapbang.auxiliary.chatbot.support.CopyRows.appearance;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.auxiliary.chatbot.domain.SyncRunStatus;
import com.hapbang.auxiliary.chatbot.out.SyncRunRepository;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.shared.appearance.domain.AppearanceResult;

import tools.jackson.databind.json.JsonMapper;

/** 챗봇 싱크 관리 API (#716). Gateway 사용자 헤더로 로그인해 보안 설정·메서드 보안을 그대로 거친다 */
class ApiV1ChatbotSyncAdminTest extends ChatbotIntegrationTestBase {

    private static final String RUNS = "/api/v1/chatbot/admin/sync/runs";
    private static final long ADMIN = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SyncRunRepository syncRunRepository;

    @Test
    @DisplayName("관리자가 실행하면 202와 회차 ID, 실행 중 같은 종류는 409, 끝나면 상세에 결과·어긋남, 목록은 최신순")
    void startAndRead() throws Exception {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0));
        CountDownLatch gate = source.hold();

        String body = mockMvc.perform(as(post(RUNS).param("type", "APPEARANCE"), "ADMIN"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusCode").value(202))
                .andExpect(jsonPath("$.data.runId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long runId = JsonMapper.builder().build().readTree(body).at("/data/runId").asLong();

        mockMvc.perform(as(post(RUNS).param("type", "APPEARANCE"), "ADMIN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYNC_ALREADY_RUNNING"));
        gate.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() ->
                syncRunRepository.findById(runId).orElseThrow().getStatus() == SyncRunStatus.SUCCEEDED);

        mockMvc.perform(as(get(RUNS + "/" + runId), "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.type").value("APPEARANCE"))
                .andExpect(jsonPath("$.data.run.trigger").value("ADMIN"))
                .andExpect(jsonPath("$.data.run.requestedBy").value(ADMIN))
                .andExpect(jsonPath("$.data.run.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.run.inserted").value(1))
                .andExpect(jsonPath("$.data.mismatches").isEmpty());
        mockMvc.perform(as(get(RUNS).param("type", "APPEARANCE"), "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(runId))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
        mockMvc.perform(as(get(RUNS).param("type", "APPEARANCE_REVIEW"), "ADMIN"))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 로그인하지 않으면 401, 없는 회차는 404, 모르는 종류는 400")
    void errors() throws Exception {
        mockMvc.perform(as(post(RUNS).param("type", "APPEARANCE"), "VERIFIED_INFLUENCER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get(RUNS), "USER")).andExpect(status().isForbidden());
        mockMvc.perform(get(RUNS)).andExpect(status().isUnauthorized());
        mockMvc.perform(as(get(RUNS + "/999999"), "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SYNC_RUN_NOT_FOUND"));
        mockMvc.perform(as(post(RUNS).param("type", "MEMBER"), "ADMIN"))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        return request.header(GatewayUserHeaderFilter.USER_ID, String.valueOf(ADMIN))
                .header(GatewayUserHeaderFilter.USER_ROLE, role);
    }
}
