package com.hapbang.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponTargetType;
import com.hapbang.coupon.domain.CouponTerms;
import com.hapbang.coupon.domain.CouponUsageStatus;
import com.hapbang.coupon.domain.DiscountType;
import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.coupon.domain.MemberCoupon;
import com.hapbang.coupon.domain.MemberCouponStatus;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.CouponUsageRepository;
import com.hapbang.coupon.out.MemberCouponRepository;
import com.hapbang.testsupport.IntegrationTest;

import com.jayway.jsonpath.JsonPath;

/**
 * 쿠폰 사용 내부 API(계산 → 예약 → 예약 상태 → 해제)를 HTTP부터 PostgreSQL까지 관통해 확인한다. 금액·중첩·추천 규칙과 거절 코드별 세부는
 * 도메인·서비스·저장소 테스트가 맡고, 여기서는 단계가 이어지는지, 한 장이 동시에 한 주문 항목에만 예약되는지(I5), Redis 없이 동작하는지(I9)만
 * 본다.
 * <p>
 * 이 모듈 테스트에는 강의 앱의 내부 API 키 보안 체인이 없다. 그래서 테스트 로그인에 INTERNAL 역할을 넣고 메서드 보안을 켜서 Facade의 역할
 * 검사가 운영처럼 돌게 한다(키 → INTERNAL 역할은 lecture-app의 {@code CouponInternalApiAccessTest}가 본다). 강의 조회는 다른 앱이라
 * mock이다. Redis는 이 테스트만 쓰는 컨테이너라 멈춰도 다른 테스트에 영향이 없고, 멈춘 컨텍스트는 재사용하지 않는다.
 */
@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
@Import(CouponUsageE2eTest.MethodSecurityConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponUsageE2eTest {

    private static final int REDIS_PORT = 6379;
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(REDIS_PORT)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    static {
        REDIS.start();
    }

    private static final long ADMIN_ID = 1L;
    private static final long SELLER_ID = 10L;
    private static final long MEMBER_A = 100L;
    private static final long MEMBER_B = 200L;
    private static final long LECTURE_ID = 7L;
    private static final long PRICE = 10_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private CouponUsageRepository couponUsageRepository;

    @MockitoBean
    private LectureCatalog lectureCatalog;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    @Test
    void 추천_계산으로_예약하고_상태를_확인한_뒤_해제하면_다시_쓸_수_있고_같은_쿠폰의_동시_예약은_하나만_성공한다() throws Exception {
        givenLecture();
        long sellerCoupon = saveSellerCoupon();
        long platformCoupon = savePlatformCoupon();
        long sellerOfA = issue(MEMBER_A, sellerCoupon);
        long platformOfA = issue(MEMBER_A, platformCoupon);

        // 추천: 판매자 10%(1,000) → 플랫폼 1,500 HP를 남은 9,000에 적용
        String calculated = calculate(MEMBER_A, "null")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended").value(true))
                .andExpect(jsonPath("$.data.optimal").value(true))
                .andExpect(jsonPath("$.data.totalDiscount").value(2_500))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> basis = JsonPath.read(calculated, "$.data.items[0].coupons");
        assertThat(basis).extracting(coupon -> ((Number) coupon.get("memberCouponId")).longValue())
                .containsExactly(sellerOfA, platformOfA);

        // 예약: 계산 근거를 그대로 보낸다
        reserve(MEMBER_A, 9001L, 90011L, "order-9001:reserve:1", basisJson(basis))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.items[0].discount").value(2_500))
                .andExpect(jsonPath("$.data.items[0].coupons[0].reservedGeneration").value(1));
        assertThat(memberCouponRepository.findStateById(sellerOfA).orElseThrow().status())
                .isEqualTo(MemberCouponStatus.RESERVED);

        mockMvc.perform(get("/api/v1/internal/coupons/reservations/{workId}", "order-9001:reserve:1")
                        .param("memberId", String.valueOf(MEMBER_A)).param("orderId", "9001").with(internal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.items[0].coupons[0].status").value("RESERVED"));

        // 해제: 만료 전이라 AVAILABLE로 돌아가고 세대는 예약 때 오른 값 그대로다
        release(9001L, "order-9001:release:1", List.of(sellerOfA, platformOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usages[0].releasedTo").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.usages[1].releasedTo").value("AVAILABLE"));
        assertThat(memberCouponRepository.findStateById(sellerOfA).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(MemberCouponStatus.AVAILABLE);
                    assertThat(state.generation()).isEqualTo(1);
                });
        mockMvc.perform(get("/api/v1/internal/coupons/reservations/{workId}", "order-9001:reserve:1")
                        .param("memberId", String.valueOf(MEMBER_A)).param("orderId", "9001").with(internal()))
                .andExpect(jsonPath("$.data.status").value("RELEASED"));

        // 해제한 예약을 같은 workId·같은 내용으로 다시 보내면 RESERVED로 재응답하지 않고 409다. 아무것도 바뀌지 않는다
        reserve(MEMBER_A, 9001L, 90011L, "order-9001:reserve:1", basisJson(basis))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_WORK_CONFLICT"));
        assertThat(memberCouponRepository.findStateById(sellerOfA).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(MemberCouponStatus.AVAILABLE);
                    assertThat(state.generation()).isEqualTo(1);
                });
        assertThat(couponUsageRepository.findAll()).extracting(usage -> usage.getStatus())
                .containsOnly(CouponUsageStatus.RELEASED).hasSize(2);

        // 해제한 쿠폰은 같은 주문 항목에 다시 예약할 수 있고, 새 세대의 새 사용 시도가 된다(이력 두 행)
        reserve(MEMBER_A, 9001L, 90011L, "order-9001:reserve:2", pairBasis(sellerOfA, platformOfA, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].coupons[0].reservedGeneration").value(2));
        assertThat(couponUsageRepository.findAll()).filteredOn(usage -> usage.getMemberCouponId() == sellerOfA)
                .extracting(usage -> usage.getGeneration() + ":" + usage.getStatus())
                .containsExactlyInAnyOrder("1:RELEASED", "2:RESERVED");

        // 동시 예약: 회원 B의 판매자 쿠폰 한 장을 두 주문이 각자의 플랫폼 쿠폰과 함께 동시에 예약한다. 공유 쿠폰을 마지막에 발급해
        // ID가 가장 크므로, 조건부 갱신은 각자의 플랫폼 쿠폰 → 공유 쿠폰 순서라 진 요청은 플랫폼 쿠폰을 갱신한 뒤 공유 쿠폰에서 진다.
        // 다만 진 요청이 이긴 요청의 커밋 뒤에 읽기 시작하면 사전 검증(세대)에서 먼저 끝나므로 이 경로는 타이밍에 달렸다.
        // 앞 장 갱신 뒤 실패의 전부 롤백은 CouponReserveRollbackTest가 결정적으로 본다
        long platformOfB1 = issue(MEMBER_B, platformCoupon);
        long platformOfB2 = issue(MEMBER_B, savePlatformCoupon());
        long sellerOfB = issue(MEMBER_B, sellerCoupon);
        List<Integer> statuses = race(
                () -> reserve(MEMBER_B, 9101L, 91011L, "order-9101:reserve:1",
                        pairBasis(sellerOfB, platformOfB1)).andReturn().getResponse().getStatus(),
                () -> reserve(MEMBER_B, 9102L, 91021L, "order-9102:reserve:1",
                        pairBasis(sellerOfB, platformOfB2)).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        // 한 장은 한 주문 항목에만 예약됐고, 진 요청의 다른 장(플랫폼 쿠폰)은 AVAILABLE·세대 0 그대로다
        assertThat(couponUsageRepository.findAll()).filteredOn(usage -> usage.getMemberCouponId() == sellerOfB)
                .singleElement().extracting(usage -> usage.getStatus()).isEqualTo(CouponUsageStatus.RESERVED);
        long loserPlatform = statuses.get(0) == 200 ? platformOfB2 : platformOfB1;
        assertThat(memberCouponRepository.findStateById(loserPlatform).orElseThrow())
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(MemberCouponStatus.AVAILABLE);
                    assertThat(state.generation()).isZero();
                });
    }

    @Test
    void Redis가_멈춰도_계산_예약_해제가_된다() throws Exception {
        givenLecture();
        long sellerCoupon = issue(MEMBER_A, saveSellerCoupon());
        long platformCoupon = issue(MEMBER_A, savePlatformCoupon());
        REDIS.stop();

        String calculated = calculate(MEMBER_A, "null")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> basis = JsonPath.read(calculated, "$.data.items[0].coupons");
        reserve(MEMBER_A, 9201L, 92011L, "order-9201:reserve:1", basisJson(basis))
                .andExpect(status().isOk());
        release(9201L, "order-9201:release:1", List.of(sellerCoupon, platformCoupon))
                .andExpect(status().isOk());
    }

    private void givenLecture() {
        given(lectureCatalog.findLecture(anyLong())).willAnswer(invocation -> Optional.of(
                new LectureSnapshot(invocation.getArgument(0), SELLER_ID, PRICE, "ON_SALE")));
    }

    /** 특정 강의 10% 판매자 쿠폰(중첩 가능) */
    private long saveSellerCoupon() {
        return couponRepository.save(Coupon.createSeller(SELLER_ID, terms(CouponTargetType.LECTURE, LECTURE_ID, PRICE,
                DiscountType.RATE, 10), Instant.now())).getId();
    }

    /** 모든 강의 1,500 HP 플랫폼 쿠폰(중첩 가능) */
    private long savePlatformCoupon() {
        return couponRepository.save(Coupon.createPlatform(ADMIN_ID, terms(CouponTargetType.ANY_LECTURE, null, null,
                DiscountType.AMOUNT, 1_500), Instant.now())).getId();
    }

    private static CouponTerms terms(CouponTargetType targetType, Long lectureId, Long basePrice,
                                     DiscountType discountType, long discountValue) {
        Instant now = Instant.now();
        return new CouponTerms("사용 e2e 쿠폰", targetType, lectureId, basePrice, discountType, discountValue, null, 10,
                now.plusSeconds(60), now.plus(Duration.ofDays(7)), ExpiryType.RELATIVE, 30, null, true);
    }

    private long issue(long memberId, long couponId) {
        Instant now = Instant.now();
        return memberCouponRepository.save(MemberCoupon.issue(memberId, couponId, "req-" + memberId + "-" + couponId,
                now, now.plus(Duration.ofDays(30)), now)).getId();
    }

    private ResultActions calculate(long memberId, String selections) throws Exception {
        return mockMvc.perform(post("/api/v1/internal/coupons/calculate").with(internal()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"memberId":%d,"items":[{"lectureId":%d,"sellerId":%d,"price":%d}],"selections":%s}
                        """.formatted(memberId, LECTURE_ID, SELLER_ID, PRICE, selections)));
    }

    private ResultActions reserve(long memberId, long orderId, long orderItemId, String workId, String couponsJson)
            throws Exception {
        return mockMvc.perform(post("/api/v1/internal/coupons/reservations").with(internal()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"memberId":%d,"orderId":%d,"workId":"%s","items":[{"orderItemId":%d,"lectureId":%d,
                        "sellerId":%d,"price":%d,"coupons":%s}]}
                        """.formatted(memberId, orderId, workId, orderItemId, LECTURE_ID, SELLER_ID, PRICE,
                        couponsJson)));
    }

    private ResultActions release(long orderId, String workId, List<Long> memberCouponIds) throws Exception {
        String usages = String.join(",", memberCouponIds.stream()
                .map(id -> "{\"memberCouponId\":%d,\"generation\":1}".formatted(id))
                .toList());
        return mockMvc.perform(post("/api/v1/internal/coupons/releases").with(internal()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId":%d,"workId":"%s","usages":[%s],"reason":"ORDER_FAILED"}
                        """.formatted(orderId, workId, usages)));
    }

    /** 계산 응답의 쿠폰 근거를 예약 요청 모양으로 */
    private static String basisJson(List<Map<String, Object>> coupons) {
        return "[" + String.join(",", coupons.stream()
                .map(coupon -> couponJson(((Number) coupon.get("memberCouponId")).longValue(),
                        ((Number) coupon.get("expectedGeneration")).longValue(),
                        ((Number) coupon.get("policyVersion")).intValue(),
                        ((Number) coupon.get("applyOrder")).intValue(),
                        ((Number) coupon.get("discount")).longValue(),
                        ((Number) coupon.get("platformAmount")).longValue(),
                        ((Number) coupon.get("sellerAmount")).longValue()))
                .toList()) + "]";
    }

    /** 판매자 10%(1,000) → 플랫폼 1,500 HP 근거(세대 0) */
    private static String pairBasis(long sellerCoupon, long platformCoupon) {
        return pairBasis(sellerCoupon, platformCoupon, 0);
    }

    private static String pairBasis(long sellerCoupon, long platformCoupon, long expectedGeneration) {
        return "[" + couponJson(sellerCoupon, expectedGeneration, 1, 1, 1_000, 0, 1_000) + ","
                + couponJson(platformCoupon, expectedGeneration, 1, 2, 1_500, 1_500, 0) + "]";
    }

    private static String couponJson(long memberCouponId, long expectedGeneration, int policyVersion, int applyOrder,
                                     long discount, long platformAmount, long sellerAmount) {
        return """
                {"memberCouponId":%d,"expectedGeneration":%d,"policyVersion":%d,"applyOrder":%d,"discount":%d,\
                "platformAmount":%d,"sellerAmount":%d}""".formatted(memberCouponId, expectedGeneration, policyVersion,
                applyOrder, discount, platformAmount, sellerAmount);
    }

    /** 두 호출을 동시에 출발시켜 결과(HTTP 상태)를 순서대로 돌려준다 */
    @SafeVarargs
    private static List<Integer> race(Callable<Integer>... calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static RequestPostProcessor internal() {
        return user("lecture-app").roles("INTERNAL");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }
}
