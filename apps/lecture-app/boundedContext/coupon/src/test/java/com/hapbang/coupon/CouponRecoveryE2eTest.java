package com.hapbang.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.PromotionBudgetPort;
import com.hapbang.coupon.app.port.PromotionBudgetPort.BudgetResult;
import com.hapbang.coupon.app.port.PromotionBudgetPort.Outcome;
import com.hapbang.coupon.config.CouponIssueConsumerKafkaConfig;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.RecoveryRunRepository;
import com.hapbang.coupon.out.redis.CouponRedisKeys;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.KafkaTestcontainersConfig;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 유실 복구 절차 1~5를 관리자 API로 실행해 재개까지 HTTP부터 PostgreSQL·Redis·Kafka까지 관통해 확인한다(operations.md "복구와 대사").
 * 단계별 전제 조건의 세부 분기는 도메인·UseCase·Lua 테스트가 맡고, 여기서는 절차가 이어지는지만 본다.
 * <ol>
 *     <li>회원 A의 접수는 DB에 발급 확정된다. 소비를 멈춘 사이 회원 B가 접수하고(Kafka에는 있다), 쿠폰의 Redis key를 지워 유실을 흉내 낸다.</li>
 *     <li>유실 동안 B의 상태 조회는 UNKNOWN이고 신규 접수는 막힌다.</li>
 *     <li>차단 → 경계 확정(차단 직후에는 진행 중이던 전송을 기다리라고 거절, 기다린 뒤 requests가 없어 재발행 0건, 유실 의심) → 결과 확정(소비가 멈춰 Kafka lag가 남아 재구축 거절) → 소비 재개 뒤 미처리 0 →
 *     재구축(재고 = 총수량 − 확정 발급 2) → 재개(새 epoch 2).</li>
 *     <li>재개 뒤 신규 접수가 새 epoch로 접수되고 DB에 발급 확정된다.</li>
 * </ol>
 * 단계별 응답은 로그 {@code [복구 기록]}으로 남긴다(보고서의 "복구 절차 1회 확인 기록"). 공유 Redis 컨테이너에서는 이 테스트가 만든 쿠폰의 key만
 * 지운다(FLUSH하지 않는다). 예산(billing)과 강의 조회는 다른 앱이라 mock이다.
 * 같은 소비 그룹의 다른 테스트 컨텍스트가 살아 있으면 partition을 나눠 가져 메시지를 가로채므로 이 컨텍스트는 클래스가 끝나면 닫는다.
 */
@Slf4j
@IntegrationTest
@AutoConfigureMockMvc
@DirtiesContext
@TestPropertySource(properties = {
        "spring.jpa.open-in-view=false",
        // 모듈 테스트 기본값(hapbang-module-test.yaml)은 Kafka 리스너·토픽 선언을 끈다. 이 테스트는 실제 Kafka로 소비한다
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        // 경계 확정은 차단 뒤 생산자 전송 최대 시간(전송 준비 1초 + max(전송 수명, 재발행 ACK)) + 여유를 기다린다. 테스트는 2초로 줄인다
        "hapbang.coupon.producer-delivery-timeout=1s",
        "hapbang.coupon.republish.ack-timeout=1s",
        "hapbang.coupon.recovery.boundary-grace=0s"
})
@Import({KafkaTestcontainersConfig.class, RedisTestcontainersConfig.class, CouponRecoveryE2eTest.MethodSecurityConfig.class})
class CouponRecoveryE2eTest {

    private static final long ADMIN_ID = 1L;
    private static final long MEMBER_A = 100L;
    private static final long MEMBER_B = 200L;
    private static final long MEMBER_C = 300L;
    private static final int QUANTITY = 5;
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private RecoveryRunRepository recoveryRunRepository;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @MockitoBean
    private PromotionBudgetPort promotionBudgetPort;

    @MockitoBean
    private LectureCatalog lectureCatalog;

    private final List<Long> createdCouponIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        consumer().start();
        for (long couponId : createdCouponIds) {
            redisTemplate.delete(couponKeys(couponId));
        }
    }

    @Test
    void Redis_유실_뒤_관리자_복구_절차로_DB_확정_결과를_재구축하고_새_epoch로_재개하면_신규_접수가_발급된다() throws Exception {
        given(promotionBudgetPort.reserve(anyString(), anyLong(), anyInt(), anyLong()))
                .willReturn(new BudgetResult(Outcome.SUCCEEDED, "reservation-1", null));
        long couponId = createAndOpenPlatformCoupon();
        await().atMost(WAIT).until(() -> consumer().isRunning() && !consumer().getAssignedPartitions().isEmpty());

        // 회원 A: 접수 → Kafka → DB 발급 확정
        requestIssue(couponId, MEMBER_A, "req-a").andExpect(status().isAccepted());
        awaitStatus(couponId, MEMBER_A, "req-a", "ISSUED");

        // 소비를 멈춘 사이 회원 B 접수: Kafka에는 있고 DB에는 아직 없다
        consumer().stop();
        await().atMost(WAIT).until(() -> !consumer().isRunning());
        requestIssue(couponId, MEMBER_B, "req-b").andExpect(status().isAccepted());
        issueStatus(couponId, MEMBER_B, "req-b").andExpect(jsonPath("$.data.status").value("PENDING"));

        // Redis 유실을 흉내 낸다: 이 쿠폰의 key만 지운다
        redisTemplate.delete(couponKeys(couponId));
        record("유실 직후 B 상태 조회", issueStatus(couponId, MEMBER_B, "req-b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN")));
        requestIssue(couponId, MEMBER_C, "req-c-lost")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_ISSUE_NOT_OPEN"));

        // 관리자가 아니면 Facade 역할 검사가 막는다(운영은 /admin URL 권한이 먼저 막는다)
        mockMvc.perform(post("/api/v1/coupons/admin/{couponId}/recovery", couponId)
                        .with(login(MEMBER_A, "USER")).with(csrf()))
                .andExpect(status().isForbidden());

        // 1. 차단
        String started = record("1 차단", admin(post("/api/v1/coupons/admin/{couponId}/recovery", couponId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.step").value("BLOCKED"))
                .andExpect(jsonPath("$.data.previousEpoch").value(Coupon.INITIAL_EPOCH))
                .andExpect(jsonPath("$.data.newEpoch").value(Coupon.INITIAL_EPOCH + 1))
                .andExpect(jsonPath("$.data.requestsLost").value(true)));
        long recoveryId = ((Number) JsonPath.read(started, "$.data.recoveryId")).longValue();
        String base = "/api/v1/coupons/admin/{couponId}/recovery/{recoveryId}";
        // 진행 중인 복구가 있으면 새 복구를 시작하지 않는다(시작 단계면 같은 복구를 돌려준다)
        admin(post("/api/v1/coupons/admin/{couponId}/recovery", couponId))
                .andExpect(jsonPath("$.data.recoveryId").value(recoveryId));
        // 순서를 건너뛰면 거절한다
        admin(post(base + "/rebuild", couponId, recoveryId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_RECOVERY_STEP_NOT_ALLOWED"));

        // 2. 경계 확정: 차단 직후에는 진행 중이던 생산자 전송이 끝나기를 기다리라고 거절한다
        record("2 경계 확정(대기 전)", admin(post(base + "/boundary", couponId, recoveryId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_RECOVERY_STEP_NOT_ALLOWED")));
        // 기다린 뒤에는 확정한다. requests가 사라져 재발행할 것이 없다
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).until(() -> admin(post(base + "/boundary", couponId,
                recoveryId)).andReturn().getResponse().getStatus() == 200);
        record("2 경계 확정", admin(post(base + "/boundary", couponId, recoveryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step").value("BOUNDARY_FIXED"))
                .andExpect(jsonPath("$.data.republishedCount").value(0))
                .andExpect(jsonPath("$.data.requestsLost").value(true)));

        // 3. 결과 확정: 소비가 멈춰 경계까지의 Kafka 메시지(B)가 남았다 → 재구축 거절
        String unresolved = record("3 결과 확정(소비 중단)", admin(post(base + "/results", couponId, recoveryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step").value("RESULTS_CHECKED")));
        assertThat(((Number) JsonPath.read(unresolved, "$.data.unresolvedCount")).longValue()).isPositive();
        record("4 재구축(미처리 남음)", admin(post(base + "/rebuild", couponId, recoveryId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_RECOVERY_STEP_NOT_ALLOWED")));

        // 소비를 다시 켜면 B가 DB에 확정되고 미처리가 0이 된다
        consumer().start();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).until(() -> {
            String body = admin(post(base + "/results", couponId, recoveryId)).andReturn().getResponse()
                    .getContentAsString();
            return ((Number) JsonPath.read(body, "$.data.unresolvedCount")).longValue() == 0;
        });
        record("3 결과 확정(소비 재개 뒤)", admin(post(base + "/results", couponId, recoveryId))
                .andExpect(jsonPath("$.data.unresolvedCount").value(0)));
        issueStatus(couponId, MEMBER_B, "req-b").andExpect(jsonPath("$.data.status").value("ISSUED"));

        // 4. 재구축: 재고 = 5 − 2, 회원 = A·B, 요청 결과 = DB에 있는 두 건, 접수는 막힌 채
        record("4 재구축", admin(post(base + "/rebuild", couponId, recoveryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step").value("REBUILT")));
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo(String.valueOf(QUANTITY - 2));
        assertThat(redisTemplate.opsForSet().members(CouponRedisKeys.users(couponId)))
                .containsExactlyInAnyOrder(String.valueOf(MEMBER_A), String.valueOf(MEMBER_B));
        assertThat(redisTemplate.opsForHash().keys(CouponRedisKeys.requests(couponId)))
                .containsExactlyInAnyOrder("req-a", "req-b");
        assertThat(control(couponId, "ready")).isEqualTo("0");
        assertThat(control(couponId, "epoch")).isEqualTo("2");
        requestIssue(couponId, MEMBER_C, "req-c-blocked").andExpect(status().isConflict());

        // 5. 재개: 수량 대사(5 = 2 + 3 + 0)가 맞아 새 epoch 활성화
        record("5 재개", admin(post(base + "/resume", couponId, recoveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeBasis\":\"Kafka에 남은 접수(B)가 DB에 확정됐고 유실 동안 신규 접수는 막혀 있었음을 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step").value("RESUMED")));
        Coupon resumed = couponRepository.findById(couponId).orElseThrow();
        assertThat(resumed.getEpoch()).isEqualTo(2L);
        assertThat(control(couponId, "ready")).isEqualTo("1");
        assertThat(control(couponId, "hold")).isNull();
        assertThat(control(couponId, "fence")).isEqualTo(String.valueOf(resumed.getVersion()));
        // 재개 뒤 늦게 온 재구축은 거절한다(DB 단계 선점 + Redis 재구축 토큰)
        record("재개 뒤 재구축", admin(post(base + "/rebuild", couponId, recoveryId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_RECOVERY_STEP_NOT_ALLOWED")));
        assertThat(control(couponId, "ready")).isEqualTo("1");
        // 유실 의심 복구를 재개한 직후라 Redis·DB 어디에도 없는 요청은 UNKNOWN 창 안이다(NOT_FOUND로 단정하지 않는다)
        record("재개 직후 없는 요청 상태 조회", issueStatus(couponId, MEMBER_C, "req-never")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNKNOWN")));

        // 재개 뒤 신규 접수는 새 epoch로 접수되고 DB에 발급 확정된다. 이미 받은 회원은 다시 받지 못한다
        requestIssue(couponId, MEMBER_C, "req-c").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.result").value("ACCEPTED"));
        requestIssue(couponId, MEMBER_A, "req-a-again").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_ISSUED"));
        awaitStatus(couponId, MEMBER_C, "req-c", "ISSUED");
        issueStatus(couponId, MEMBER_A, "req-a").andExpect(jsonPath("$.data.status").value("ISSUED"));
        assertThat(couponRepository.findById(couponId).orElseThrow().getIssuedCount()).isEqualTo(3);

        record("복구 실행 목록", admin(get("/api/v1/coupons/admin/{couponId}/recovery", couponId))
                .andExpect(jsonPath("$.data[0].step").value("RESUMED")));
        recoveryRunRepository.findByCouponIdOrderByIdDesc(couponId).forEach(run -> log.info(
                "[복구 기록] recovery_run id={}, couponId={}, previousEpoch={}, newEpoch={}, step={}, requestsLost={}, "
                        + "republishedAt={}, republishedCount={}, kafkaBoundary={}, dltBoundary={}, unresolvedCount={}, "
                        + "unresolvedDetail={}, resumeBasis={}, resumeEvidence={}, startedBy={}, createdAt={}, blockedAt={}, "
                        + "resumedAt={}",
                run.getId(), run.getCouponId(), run.getPreviousEpoch(), run.getNewEpoch(), run.getStep(),
                run.isRequestsLost(), run.getRepublishedAt(), run.getRepublishedCount(), run.getKafkaBoundary(),
                run.getDltBoundary(), run.getUnresolvedCount(), run.getUnresolvedDetail(), run.getResumeBasis(),
                run.getResumeEvidence(), run.getStartedBy(), run.getCreatedAt(), run.getBlockedAt(), run.getResumedAt()));
    }

    private MessageListenerContainer consumer() {
        return listenerRegistry.getListenerContainer(CouponIssueConsumerKafkaConfig.LISTENER_ID);
    }

    private long createAndOpenPlatformCoupon() throws Exception {
        Instant now = Instant.now();
        String body = """
                {"name":"선착순 할인","targetType":"ANY_LECTURE","discountType":"AMOUNT","discountValue":1000,"quantity":%d,
                 "issueStartAt":"%s","issueEndAt":"%s","expiryType":"RELATIVE","expiryDays":30,"stackable":false}
                """.formatted(QUANTITY, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(7)));
        String response = admin(post("/api/v1/coupons/admin").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long couponId = ((Number) JsonPath.read(response, "$.data.couponId")).longValue();
        createdCouponIds.add(couponId);
        admin(post("/api/v1/coupons/admin/{couponId}/open", couponId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        return couponId;
    }

    private void awaitStatus(long couponId, long memberId, String requestId, String expected) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> expected.equals(JsonPath.read(
                issueStatus(couponId, memberId, requestId).andReturn().getResponse().getContentAsString(),
                "$.data.status")));
    }

    private String record(String step, ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        log.info("[복구 기록] {} → HTTP {} {}", step, result.andReturn().getResponse().getStatus(), body);
        return body;
    }

    private ResultActions admin(MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.with(login(ADMIN_ID, "ADMIN")).with(csrf()));
    }

    private ResultActions requestIssue(long couponId, long memberId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/v1/coupons/{couponId}/issue-requests", couponId)
                .with(login(memberId, "USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"issueRequestId\":\"" + requestId + "\"}"));
    }

    private ResultActions issueStatus(long couponId, long memberId, String requestId) throws Exception {
        return mockMvc.perform(get("/api/v1/coupons/{couponId}/issue-requests/{requestId}", couponId, requestId)
                .with(login(memberId, "USER")));
    }

    private Object control(long couponId, String field) {
        return redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), field);
    }

    private static List<String> couponKeys(long couponId) {
        return List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId),
                CouponRedisKeys.soldOut(couponId));
    }

    /** 회원 ID가 이름(getName)인 로그인. 운영에서는 강의 앱의 Gateway 사용자 헤더 필터가 같은 모양으로 만든다 */
    private static RequestPostProcessor login(long memberId, String role) {
        return user(String.valueOf(memberId)).roles(role);
    }

    /** 운영에서는 강의 앱 SecurityConfig가 켜는 메서드 보안. 이 모듈 테스트에는 그 설정이 없어 여기서 켠다 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }
}
