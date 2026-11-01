package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.out.ChatDeliveryReprocessAuditRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.TestLogin;

/**
 * 관리자 전달 작업 API의 보안·입력 검증 보강 (재검증). {@link ChatDeliveryAdminApiTest}가 다루지 않은 것:
 * CSRF 없는 재처리, 기본 회원(USER), 잘못된 stream·UUID·cursor·size 형식, 클라이언트가 보낸 요청자 값 무시, 오류 응답의
 * 내부 정보 노출.
 */
@AutoConfigureMockMvc
@DirtiesContext
class ChatDeliveryAdminApiSecurityTest extends ChatDeliveryTestSupport {

    private static final String URL = "/api/v1/chats/admin/delivery-jobs";
    private static final long ADMIN = 901L;

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatDeliveryReprocessAuditRepository auditRepository;

    @Test
    void 관리자라도_CSRF_토큰_없는_재처리는_403이고_상태를_바꾸지_않는다() throws Exception {
        UUID deliveryId = isolate(send("격리"));

        mockMvc.perform(post(URL + "/DELIVERY/" + deliveryId + "/reprocess").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isForbidden());

        assertThat(auditRepository.count()).isZero();
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        assertThat(checkpoint(deliveryId).getGeneration()).isZero();
    }

    @Test
    void 기본_회원은_조회도_재처리도_403이다() throws Exception {
        UUID deliveryId = isolate(send("격리"));

        mockMvc.perform(get(URL).with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/DELIVERY/" + deliveryId + "/reprocess").with(csrf())
                        .with(TestLogin.as(1L, Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/DELIVERY/" + deliveryId + "/reprocess").with(csrf())
                        .with(TestLogin.as(1L, Role.INFLUENCER)))
                .andExpect(status().isForbidden());

        assertThat(auditRepository.count()).isZero();
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.DEAD);
    }

    @Test
    void 요청자는_인증된_관리자에서_정하고_요청_값으로_바꿀_수_없다() throws Exception {
        UUID deliveryId = isolate(send("격리"));

        mockMvc.perform(post(URL + "/DELIVERY/" + deliveryId + "/reprocess").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN))
                        .param("requestedBy", "1")
                        .param("adminId", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedBy\":1,\"adminId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedBy").value(ADMIN));

        assertThat(auditRepository.findByConsumerGroupAndJobIdOrderByIdAsc(ChatJobStream.DELIVERY.consumerGroup(),
                deliveryId)).singleElement().satisfies(audit -> assertThat(audit.getRequestedBy()).isEqualTo(ADMIN));
    }

    @Test
    void 잘못된_stream_작업_ID_cursor_size는_400이고_내부_정보를_드러내지_않는다() throws Exception {
        assertBadRequest(mockMvc.perform(get(URL).param("stream", "UNKNOWN").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andReturn());
        assertBadRequest(mockMvc.perform(get(URL).param("cursor", "0").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andReturn());
        assertBadRequest(mockMvc.perform(get(URL).param("cursor", "-5").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andReturn());
        assertBadRequest(mockMvc.perform(get(URL).param("cursor", "abc").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andReturn());
        assertBadRequest(mockMvc.perform(get(URL).param("size", "x").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andReturn());
        assertBadRequest(mockMvc.perform(get(URL).param("size", "-1").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andReturn());
        assertBadRequest(mockMvc.perform(get(URL).param("size", "2147483648").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andReturn());

        assertBadRequest(mockMvc.perform(post(URL + "/UNKNOWN/" + UUID.randomUUID() + "/reprocess").with(csrf())
                .with(TestLogin.as(ADMIN, Role.ADMIN))).andReturn());
        assertBadRequest(mockMvc.perform(post(URL + "/DELIVERY/not-a-uuid/reprocess").with(csrf())
                .with(TestLogin.as(ADMIN, Role.ADMIN))).andReturn());
        assertBadRequest(mockMvc.perform(post(URL + "/DELIVERY/' or 1=1 --/reprocess").with(csrf())
                .with(TestLogin.as(ADMIN, Role.ADMIN))).andReturn());

        assertThat(auditRepository.count()).isZero();
    }

    private static void assertBadRequest(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
        assertThat(body).doesNotContain("Exception", "java.", "org.springframework", "com.hapbang", "SQL",
                "\tat ");
    }

    /** 발행을 시도 상한(3회)까지 실패시켜 격리한다 */
    private UUID isolate(UUID deliveryId) {
        relayAll(ChatJobStream.DELIVERY);
        fanout.failNext(3);
        QueuedJob job = queue.readNew(ChatJobStream.DELIVERY, WORKER, 10, Duration.ofMillis(50)).getFirst();
        for (int i = 0; i < 3; i++) {
            handleNow(job, deliveryId);
        }
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.DEAD);
        return deliveryId;
    }
}
