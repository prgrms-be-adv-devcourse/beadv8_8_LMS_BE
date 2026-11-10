package com.hapbang.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.PromotionBudget;
import com.hapbang.coupon.app.port.PromotionBudget.BudgetResult;
import com.hapbang.coupon.app.port.PromotionBudget.Outcome;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.IssueRequestRecord;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.redis.CouponRedisKeys;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import com.jayway.jsonpath.JsonPath;

/**
 * 플랫폼 쿠폰 발행 → 열기 → 발급 접수 → 발급 요청 상태를 HTTP부터 PostgreSQL·Redis까지 관통해 확인한다. 응답 코드별 세부 규칙은
 * 도메인·UseCase·Lua 테스트가 맡고, 여기서는 단계가 이어지는지와 같은 요청 재전송(I4)·회원당 한 장(I2)만 본다.
 * <p>
 * 이 모듈 테스트에는 강의 앱의 보안 설정(Gateway 사용자 헤더 필터, 메서드 보안)이 없다. 그래서 Spring Security 기본 필터에
 * 테스트 로그인({@code user(...)})과 CSRF 토큰을 넣고, 메서드 보안은 이 테스트 설정으로 켜서 Facade의 역할 검사가 운영처럼 돌게 한다.
 * 예산(billing)과 강의 조회는 다른 앱이라 mock이다.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
@Import({RedisTestcontainersConfig.class, CouponIssueE2eTest.MethodSecurityConfig.class})
class CouponIssueE2eTest {

    private static final long ADMIN_ID = 1L;
    private static final long MEMBER_A = 100L;
    private static final long MEMBER_B = 200L;
    private static final int QUANTITY = 10;
    private static final long DISCOUNT_HP = 1_000L;
    private static final int EXPIRY_DAYS = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CouponRepository couponRepository;

    @MockitoBean
    private PromotionBudget promotionBudget;

    @MockitoBean
    private LectureCatalog lectureCatalog;

    private final List<Long> createdCouponIds = new ArrayList<>();

    @AfterEach
    void cleanUpRedis() {
        // 공유 Redis 컨테이너에서 이 테스트가 만든 쿠폰 key만 지운다
        for (long couponId : createdCouponIds) {
            redisTemplate.delete(List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                    CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId),
                    CouponRedisKeys.soldOut(couponId)));
        }
    }

    @Test
    void 연_플랫폼_쿠폰을_접수하면_Redis에_한_번만_기록되고_재전송은_같은_결과_다른_요청은_이미_발급이다() throws Exception {
        given(promotionBudget.reserve(anyString(), anyLong(), anyInt(), anyLong()))
                .willReturn(new BudgetResult(Outcome.SUCCEEDED, "reservation-1", null));

        // 관리자: 모든 강의 대상 1,000 HP 10장 발행(DRAFT) → 열기(예산 예약 → Redis 준비 → OPEN)
        long couponId = createPlatformCoupon();
        // 관리자가 아니면 Facade 역할 검사가 막는다(운영은 /admin URL 권한이 먼저 막는다)
        mockMvc.perform(post("/api/v1/coupons/admin/{couponId}/open", couponId).with(login(MEMBER_A, "USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/coupons/admin/{couponId}/open", couponId).with(login(ADMIN_ID, "ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        verify(promotionBudget).reserve("coupon-" + couponId + "-reserve-v" + Coupon.INITIAL_POLICY_VERSION,
                couponId, Coupon.INITIAL_POLICY_VERSION, DISCOUNT_HP * QUANTITY);
        verifyNoInteractions(lectureCatalog);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo(String.valueOf(QUANTITY));
        // Redis 접수는 DB OPEN 커밋의 version을 fence로 열린다. 다시 열면(멱등) version을 올려 같은 fence로 다시 연다
        long openedVersion = couponVersion(couponId);
        assertThat(issueFence(couponId)).isEqualTo(openedVersion);
        mockMvc.perform(post("/api/v1/coupons/admin/{couponId}/open", couponId).with(login(ADMIN_ID, "ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        assertThat(couponVersion(couponId)).isEqualTo(openedVersion + 1);
        assertThat(issueFence(couponId)).isEqualTo(openedVersion + 1);

        // 회원 A 접수: requests 1건, stock −1, users에 A. Kafka 전달은 발급 전달 e2e가 본다(여기에는 broker가 없어 전송이 실패해도
        // 접수는 ACCEPTED다)
        String requestId = "req-a-1";
        String acceptedResponse = requestIssue(couponId, MEMBER_A, requestId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.issueRequestId").value(requestId))
                .andExpect(jsonPath("$.data.couponId").value(couponId))
                .andExpect(jsonPath("$.data.result").value("ACCEPTED"))
                .andReturn().getResponse().getContentAsString();

        Object record = redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), requestId);
        IssueRequestRecord accepted = IssueRequestRecord.parse((String) record);
        assertThat(accepted.memberId()).isEqualTo(MEMBER_A);
        long issuedAt = accepted.acceptedAt().toEpochMilli();
        long expiresAt = accepted.expiresAt().toEpochMilli();
        // 만료 시각은 DB를 읽지 않고 Redis 준비 값(접수 기준 30일)으로 Lua가 정한다. 접수 응답도 같은 값이다
        assertThat(expiresAt - issuedAt).isEqualTo(Duration.ofDays(EXPIRY_DAYS).toMillis());
        assertThat(responseExpiresAt(acceptedResponse)).isEqualTo(Instant.ofEpochMilli(expiresAt));
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId)))
                .isEqualTo(String.valueOf(QUANTITY - 1));
        assertThat(redisTemplate.opsForSet().members(CouponRedisKeys.users(couponId)))
                .containsExactly(String.valueOf(MEMBER_A));
        // 접수 기록에는 접수 당시 issue-control의 epoch가 남는다
        assertThat(accepted.epoch()).isNotNull();
        assertThat(record).isEqualTo(MEMBER_A + "|" + issuedAt + "|ACCEPTED|" + expiresAt + "|e" + accepted.epoch());

        // 같은 요청 ID 재전송(I4): 같은 접수(최초 만료 시각)로 응답하고 기록·stock을 바꾸지 않는다
        String again = requestIssue(couponId, MEMBER_A, requestId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.result").value("ALREADY_ACCEPTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(responseExpiresAt(again)).isEqualTo(Instant.ofEpochMilli(expiresAt));
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId)))
                .isEqualTo(String.valueOf(QUANTITY - 1));
        assertThat(redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), requestId)).isEqualTo(record);

        // 상태 조회: DB 확정 전이라 PENDING
        issueStatus(couponId, MEMBER_A, requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueRequestId").value(requestId))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // 같은 회원의 다른 요청 ID(I2): 이미 발급으로 거절하고 stock·requests는 그대로
        requestIssue(couponId, MEMBER_A, "req-a-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_ISSUED"));
        assertThat(redisTemplate.opsForHash().keys(CouponRedisKeys.requests(couponId))).containsExactly(requestId);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId)))
                .isEqualTo(String.valueOf(QUANTITY - 1));

        // 다른 회원이 A의 요청 ID로 조회하면 없는 요청이다
        issueStatus(couponId, MEMBER_B, requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_FOUND"));
    }

    private long createPlatformCoupon() throws Exception {
        Instant now = Instant.now();
        String body = """
                {"name":"선착순 할인","targetType":"ANY_LECTURE","discountType":"AMOUNT","discountValue":%d,"quantity":%d,
                 "issueStartAt":"%s","issueEndAt":"%s","expiryType":"RELATIVE","expiryDays":%d,"stackable":false}
                """.formatted(DISCOUNT_HP, QUANTITY, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(7)),
                EXPIRY_DAYS);
        String response = mockMvc.perform(post("/api/v1/coupons/admin")
                        .with(login(ADMIN_ID, "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("PLATFORM"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long couponId = ((Number) JsonPath.read(response, "$.data.couponId")).longValue();
        createdCouponIds.add(couponId);
        return couponId;
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

    private static Instant responseExpiresAt(String response) {
        return Instant.parse(JsonPath.read(response, "$.data.expiresAt"));
    }

    private long couponVersion(long couponId) {
        return couponRepository.findById(couponId).orElseThrow().getVersion();
    }

    private long issueFence(long couponId) {
        return Long.parseLong((String) redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "fence"));
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
