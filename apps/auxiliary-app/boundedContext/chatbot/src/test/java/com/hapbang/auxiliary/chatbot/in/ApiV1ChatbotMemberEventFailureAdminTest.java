package com.hapbang.auxiliary.chatbot.in;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.auxiliary.chatbot.app.CopyApplier;
import com.hapbang.auxiliary.chatbot.in.kafka.MemberEventDeadLetterRecorder;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.CopyFixtures;
import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserMaskedEvent;

/** 회원 이벤트 실패 관리 API (#976). Gateway 사용자 헤더로 로그인해 보안 설정·메서드 보안을 그대로 거친다 */
class ApiV1ChatbotMemberEventFailureAdminTest extends ChatbotIntegrationTestBase {

    private static final String FAILURES = "/api/v1/chatbot/admin/member-event-failures";
    private static final String RETRY = FAILURES + "/retry";
    private static final long ADMIN = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CopyApplier copyApplier;

    @Autowired
    private MemberEventDeadLetterRecorder recorder;

    @BeforeEach
    void admin() {
        copyApplier.apply(CopyFixtures.member(ADMIN, Role.ADMIN, UserStatus.ACTIVE, 0L));
    }

    @Test
    @DisplayName("관리자는 PENDING 실패 목록에서 이유(예외 클래스·잘라 낸 메시지·시각)를 보고, 골라 다시 처리해 건별 결과를 받는다. "
            + "처리된 건은 기본 목록에서 빠진다")
    void listAndRetry() throws Exception {
        recorder.record(new ConsumerRecord<>("member.events.v1", 0, 5L, "6",
                new UserMaskedEvent(6L, Instant.parse("2026-11-09T01:00:00Z"))),
                new ListenerExecutionFailedException("리스너 실패",
                        new IllegalStateException("처리 실패 leak@example.com")));
        long id = jdbcTemplate.queryForObject("SELECT id FROM chatbot.member_event_failure", Long.class);

        mockMvc.perform(as(get(FAILURES), "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(id))
                .andExpect(jsonPath("$.data.content[0].userId").value(6))
                .andExpect(jsonPath("$.data.content[0].eventType").value("MASKED"))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.content[0].replayable").value(true))
                .andExpect(jsonPath("$.data.content[0].sourcePartition").value(0))
                .andExpect(jsonPath("$.data.content[0].sourceOffset").value(5))
                .andExpect(jsonPath("$.data.content[0].exceptionClass")
                        .value(IllegalStateException.class.getName()))
                .andExpect(jsonPath("$.data.content[0].message").value("처리 실패 [이메일]"))
                .andExpect(jsonPath("$.data.content[0].failedAt").exists())
                .andExpect(content().string(not(containsString("leak@example.com"))))
                .andExpect(content().string(not(containsString("stackTrace"))));

        mockMvc.perform(as(post(RETRY).contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[" + id + "]}"), "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].result").value("SUCCEEDED"));

        mockMvc.perform(as(get(FAILURES), "ADMIN"))
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
        mockMvc.perform(as(get(FAILURES).param("status", "RESOLVED"), "ADMIN"))
                .andExpect(jsonPath("$.data.content[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.content[0].resolvedBy").value(ADMIN));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 로그인하지 않으면 401, 사본에서 관리자가 아니면 403 NOT_ADMIN, 고른 ID가 100개를 넘으면 400")
    void errors() throws Exception {
        mockMvc.perform(as(get(FAILURES), "USER")).andExpect(status().isForbidden());
        mockMvc.perform(as(post(RETRY).contentType(MediaType.APPLICATION_JSON).content("{}"), "VERIFIED_INFLUENCER"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(FAILURES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(FAILURES)
                        .header(GatewayUserHeaderFilter.USER_ID, "2")
                        .header(GatewayUserHeaderFilter.USER_ROLE, "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ADMIN"));

        String ids = LongStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(","));
        mockMvc.perform(as(post(RETRY).contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[" + ids + "]}"), "ADMIN"))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
        return request.header(GatewayUserHeaderFilter.USER_ID, String.valueOf(ADMIN))
                .header(GatewayUserHeaderFilter.USER_ROLE, role);
    }
}
