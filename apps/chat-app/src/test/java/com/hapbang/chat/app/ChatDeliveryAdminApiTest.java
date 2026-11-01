package com.hapbang.chat.app;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.chat.app.dto.QueuedJob;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.out.ChatDeliveryReprocessAuditRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.TestLogin;

/**
 * 관리자 격리 전달 작업 조회·운영 재처리 API (2026-10-30 사용자 결정).
 */
@AutoConfigureMockMvc
@DirtiesContext
class ChatDeliveryAdminApiTest extends ChatDeliveryTestSupport {

    private static final String URL = "/api/v1/chats/admin/delivery-jobs";
    private static final long ADMIN = 900L;

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatDeliveryReprocessAuditRepository auditRepository;

    @Test
    void 관리자는_격리한_작업을_페이지로_조회한다() throws Exception {
        UUID older = isolate(send("먼저 격리"));
        UUID newer = isolate(send("나중 격리"));
        queue.enqueue(ChatJobStream.DELIVERY, Map.of("garbage", "1"));
        pollAll(ChatJobStream.DELIVERY, WORKER);

        String body = mockMvc.perform(get(URL).param("size", "2").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                // 형식 오류 격리(outbox 원본 없음)가 가장 최근이다
                .andExpect(jsonPath("$.data.items[0].deadReason").value("MALFORMED_ENTRY"))
                .andExpect(jsonPath("$.data.items[0].reprocessable").value(false))
                .andExpect(jsonPath("$.data.items[0].outboxStatus").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].jobId").value(newer.toString()))
                .andExpect(jsonPath("$.data.items[1].stream").value("DELIVERY"))
                .andExpect(jsonPath("$.data.items[1].consumerGroup").value("chat-delivery-v1"))
                .andExpect(jsonPath("$.data.items[1].deadReason").value("PUBLISH_RETRY_EXHAUSTED"))
                .andExpect(jsonPath("$.data.items[1].publishAttemptCount").value(3))
                .andExpect(jsonPath("$.data.items[1].outboxStatus").value("ENQUEUED"))
                .andExpect(jsonPath("$.data.items[1].reprocessable").value(true))
                .andExpect(jsonPath("$.data.items[1].publishError").isNotEmpty())
                .andExpect(jsonPath("$.data.items[1].isolatedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.nextCursor").isNumber())
                .andReturn().getResponse().getContentAsString();
        String cursor = body.replaceAll(".*\"nextCursor\":(\\d+).*", "$1");

        mockMvc.perform(get(URL).param("size", "2").param("cursor", cursor).with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].jobId").value(older.toString()))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
        mockMvc.perform(get(URL).param("stream", "CONTROL").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 관리자가_재처리하면_요청한_관리자를_감사_기록에_남기고_다시_발행한다() throws Exception {
        UUID deliveryId = isolate(send("격리"));

        mockMvc.perform(post(URL + "/DELIVERY/" + deliveryId + "/reprocess").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.stream").value("DELIVERY"))
                .andExpect(jsonPath("$.data.jobId").value(deliveryId.toString()))
                .andExpect(jsonPath("$.data.requestedBy").value(ADMIN));

        assertThat(auditRepository.findByConsumerGroupAndJobIdOrderByIdAsc(ChatJobStream.DELIVERY.consumerGroup(),
                deliveryId)).singleElement().satisfies(audit -> {
                    assertThat(audit.getRequestedBy()).isEqualTo(ADMIN);
                    assertThat(audit.getPreviousPublishStatus()).isEqualTo("DEAD");
                });
        pollAll(ChatJobStream.DELIVERY, WORKER);
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PUBLISHED);
        assertThat(checkpoint(deliveryId).getGeneration()).isEqualTo(1);
    }

    @Test
    void 격리하지_않은_작업과_원본이_없는_작업은_재처리할_수_없다() throws Exception {
        UUID published = send("발행 완료");
        relayAll(ChatJobStream.DELIVERY);
        pollAll(ChatJobStream.DELIVERY, WORKER);

        mockMvc.perform(post(URL + "/DELIVERY/" + published + "/reprocess").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_JOB_NOT_ISOLATED"));
        mockMvc.perform(post(URL + "/DELIVERY/" + UUID.randomUUID() + "/reprocess").with(csrf())
                        .with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_JOB_NOT_FOUND"));
        assertThat(auditRepository.count()).isZero();
        assertThat(checkpoint(published).getGeneration()).isZero();
    }

    @Test
    void 조회_크기가_범위를_벗어나면_400이다() throws Exception {
        mockMvc.perform(get(URL).param("size", "101").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get(URL).param("size", "0").with(TestLogin.as(ADMIN, Role.ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 관리자가_아니면_403_비로그인은_401이다() throws Exception {
        UUID deliveryId = isolate(send("격리"));

        mockMvc.perform(get(URL).with(TestLogin.as(1L, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/DELIVERY/" + deliveryId + "/reprocess").with(csrf())
                        .with(TestLogin.as(1L, Role.VERIFIED_INFLUENCER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/DELIVERY/" + deliveryId + "/reprocess").with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(auditRepository.count()).isZero();
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.DEAD);
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
