package com.hapbang.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.coupon.app.CouponBudgetWorkUseCase;
import com.hapbang.coupon.app.CouponCapacitySupport;
import com.hapbang.coupon.app.CouponOpenUseCase;
import com.hapbang.coupon.app.CouponSupport;
import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.LectureCatalog.LectureSnapshot;
import com.hapbang.coupon.app.port.PromotionBudget;
import com.hapbang.coupon.out.billing.HttpPromotionBudget;
import com.hapbang.testsupport.billing.RealBillingTestFixture;
import com.hapbang.coupon.app.port.PromotionBudget.BudgetResult;
import com.hapbang.coupon.app.port.PromotionBudget.Outcome;
import com.hapbang.coupon.domain.BudgetWork;
import com.hapbang.coupon.domain.BudgetWorkKind;
import com.hapbang.coupon.domain.BudgetWorkStatus;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
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
import com.hapbang.coupon.out.redis.CouponRedisProperties;
import com.hapbang.coupon.global.exception.CouponErrorCode;
import com.hapbang.coupon.global.exception.CouponException;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import com.jayway.jsonpath.JsonPath;

/**
 * 쿠폰 사용 내부 API(계산 → 예약 → 예약 상태 → 해제)를 HTTP부터 PostgreSQL까지 관통해 확인한다. 금액·중첩·추천 규칙과 거절 코드별 세부는
 * 도메인·서비스·저장소 테스트가 맡고, 여기서는 단계가 이어지는지, 한 장이 동시에 한 주문 항목에만 예약되는지(I5), Redis 없이 동작하는지(I9)만
 * 본다.
 * <p>
 * 이 모듈 테스트에는 강의 앱의 내부 API 키 보안 체인이 없다. 그래서 테스트 로그인에 INTERNAL 역할을 넣고 메서드 보안을 켜서 Facade의 역할
 * 검사가 운영처럼 돌게 한다(키 → INTERNAL 역할은 lecture-app의 {@code CouponInternalApiAccessTest}가 본다). 강의 조회는 다른 앱이라
 * mock이다. Redis는 공통 컨테이너({@link RedisTestcontainersConfig})를 쓰고, Redis 장애는 그 컨테이너를 잠시 멈춰({@code withRedisPaused})
 * 만든다. 앱의 Redis 연결이 그 컨테이너를 가리키므로, 이 클래스만의 컨테이너를 멈추는 방식은 앱의 Redis를 멈추지 못했다.
 */
@IntegrationTest
@AutoConfigureMockMvc
// 판매자·플랫폼 쿠폰을 함께 추천·예약·해제하는 흐름을 보므로 플랫폼 재원 활성화를 명시한다.
// 이 클래스는 mock·메서드 보안 설정으로 컨텍스트를 따로 띄우므로 속성을 더해도 컨텍스트가 늘지 않는다
// Redis 명령 제한 시간은 운영 application.yaml과 같은 값이다(모듈 테스트는 그 파일을 읽지 않는다)
@TestPropertySource(properties = {"spring.jpa.open-in-view=false", "hapbang.coupon.platform-funding-enabled=true",
        "spring.data.redis.timeout=2s", "spring.data.redis.connect-timeout=1s"})
@Import({CouponUsageE2eTest.MethodSecurityConfig.class, RedisTestcontainersConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponUsageE2eTest {

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired private com.hapbang.coupon.app.CouponBudgetReleaseRecorder budgetReleaseRecorder;
    @Autowired private com.hapbang.coupon.app.CouponOrderConsumerUseCase couponOrderConsumerUseCase;
    @Autowired private com.hapbang.coupon.app.MemberCouponExpiryUseCase memberCouponExpiryUseCase;
    @Autowired private com.hapbang.coupon.out.BudgetWorkRepository budgetWorkRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager budgetTransactionManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate budgetJdbc;
    @Autowired private CouponIssueStore couponIssueStore;
    @Autowired private CouponCapacitySupport capacitySupport;
    @Autowired private CouponRedisProperties redisProperties;

    @Autowired private RealBillingTestFixture billing;
    @MockitoSpyBean private HttpPromotionBudget promotionBudget;

    @BeforeEach
    void observeRealBudgetCalls() {
        doAnswer(invocation -> { assertBudgetHttpOutsideTransaction(); return invocation.callRealMethod(); })
                .when(promotionBudget).status(anyString());
        doAnswer(invocation -> { assertBudgetHttpOutsideTransaction(); return invocation.callRealMethod(); })
                .when(promotionBudget).reserve(anyString(), anyLong(), anyInt(), anyLong());
        doAnswer(invocation -> { assertBudgetHttpOutsideTransaction(); return invocation.callRealMethod(); })
                .when(promotionBudget).release(anyString(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void foreground의_늦은_거절은_재선점한_배치의_성공을_덮지_못하며_같은_작업으로_발급을_연다() throws Exception {
        Instant now = Instant.now();
        long couponId = budgetCoupon(now, false);
        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        String workId = coupon.budgetReserveWorkId();
        CountDownLatch foregroundEntered = new CountDownLatch(1);
        CountDownLatch finishForeground = new CountDownLatch(1);
        CountDownLatch batchEntered = new CountDownLatch(1);
        CountDownLatch finishBatch = new CountDownLatch(1);
        billing.afterNextResponse("/reservations", () -> {
            foregroundEntered.countDown();
            awaitBudgetGate(finishForeground);
        });
        CouponOpenUseCase opener = budgetOpener(now, couponIssueStore);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<CouponException> foreground = workers.submit(() -> {
                try {
                    opener.open(ADMIN_ID, couponId);
                    throw new AssertionError("재선점된 PENDING 작업은 503이어야 한다");
                } catch (CouponException exception) {
                    return exception;
                }
            });
            assertThat(foregroundEntered.await(10, TimeUnit.SECONDS)).isTrue();
            // 첫 요청은 실제 프로모션 잔액 부족으로 거절됐다. 충전 뒤 같은 작업을 재선점한다.
            fundPromotion(coupon.budgetAmount());
            budgetJdbc.update("update coupon.budget_work set lease_until = ? where work_id = ?",
                    java.sql.Timestamp.from(now.minusSeconds(1)), workId);
            billing.afterNextResponse("/reservations", () -> {
                batchEntered.countDown();
                awaitBudgetGate(finishBatch);
            });
            Future<?> batch = workers.submit(() -> budgetWorker(now).runDue());
            assertThat(batchEntered.await(10, TimeUnit.SECONDS)).isTrue();
            finishForeground.countDown();
            assertThat(foreground.get(10, TimeUnit.SECONDS).getErrorCode())
                    .isEqualTo(CouponErrorCode.PROMOTION_BUDGET_UNAVAILABLE);
            assertThat(budgetWorkRepository.findById(workId).orElseThrow()).satisfies(work -> {
                assertThat(work.getAttempt()).isEqualTo(2);
                assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
            });
            finishBatch.countDown();
            batch.get(10, TimeUnit.SECONDS);
            assertThat(budgetWorkRepository.findById(workId).orElseThrow().getStatus()).isEqualTo(BudgetWorkStatus.DONE);
            assertThat(opener.open(ADMIN_ID, couponId).status()).isEqualTo(CouponStatus.OPEN);
            assertThat(budgetWorkRepository.findAll()).singleElement().extracting(BudgetWork::getWorkId).isEqualTo(workId);
            assertThat(promotionBudget.status(workId)).satisfies(result -> {
                assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
                assertThat(result.reservationId()).isEqualTo(couponRepository.findById(couponId).orElseThrow().getBudgetReservationId());
                assertThat(result.work().amount()).isEqualTo(coupon.budgetAmount());
            });
        } finally {
            finishForeground.countDown();
            finishBatch.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void UNKNOWN_예약이_발급기한_뒤_확정되면_DRAFT의_전액_해제를_같은_커밋에_남긴다() {
        Instant now = Instant.now();
        long couponId = budgetCoupon(now, false);
        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        String workId = coupon.budgetReserveWorkId();
        fundPromotion(coupon.budgetAmount());
        budgetWorkRepository.saveAndFlush(BudgetWork.reserve(coupon, 1, now));
        billing.dropNextResponse("/reservations");
        budgetWorker(now).execute(workId);
        assertThat(budgetWorkRepository.findAll()).singleElement().extracting(BudgetWork::getStatus)
                .isEqualTo(BudgetWorkStatus.PENDING);
        Instant afterEnd = coupon.getIssueEndAt().plusSeconds(1);
        budgetWorker(afterEnd).runDue();
        assertThat(budgetWorkRepository.findById(workId).orElseThrow().getStatus()).isEqualTo(BudgetWorkStatus.DONE);
        Coupon expiredDraft = couponRepository.findById(couponId).orElseThrow();
        assertThat(expiredDraft.getStatus()).isEqualTo(CouponStatus.DRAFT);
        assertThat(expiredDraft.getBudgetReservationId()).matches("PB-[1-9][0-9]*");
        assertThat(budgetWorkRepository.findByCouponIdAndKindOrderByCreatedAtAsc(couponId, BudgetWorkKind.RELEASE))
                .singleElement().satisfies(release -> {
                    assertThat(release.getWorkId()).isEqualTo("coupon-" + couponId + "-release-unissued-v1");
                    assertThat(release.getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
                    assertThat(release.getAmount()).isEqualTo(7_500);
                    assertThat(release.getTarget()).isEqualTo("UNISSUED:5");
                });
        assertThat(reservation(expiredDraft.getBudgetReservationId()).releasedHp()).isZero();
        budgetWorker(afterEnd).runDue();
        budgetWorker(afterEnd.plusSeconds(1)).runDue();
        assertThat(reservation(expiredDraft.getBudgetReservationId()).releasedHp()).isEqualTo(7_500);
        assertThat(budgetWorkRepository.findByCouponIdAndKindOrderByCreatedAtAsc(couponId, BudgetWorkKind.RELEASE)).hasSize(1);
    }

    @Test
    void 예약_DONE뒤_Redis준비가_실패한_DRAFT도_기한뒤_스캔하여_한번만_전액_해제한다() {
        Instant now = Instant.now();
        long couponId = budgetCoupon(now, false);
        fundPromotion(couponRepository.findById(couponId).orElseThrow().budgetAmount());
        CouponIssueStore failedRedis = mock(CouponIssueStore.class);
        doThrow(new CouponIssueStoreUnavailableException("Redis 준비 실패", null)).when(failedRedis).prepare(any());
        assertThatThrownBy(() -> budgetOpener(now, failedRedis).open(ADMIN_ID, couponId))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        Coupon draft = couponRepository.findById(couponId).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo(CouponStatus.DRAFT);
        assertThat(budgetWorkRepository.findById(draft.budgetReserveWorkId()).orElseThrow().getStatus())
                .isEqualTo(BudgetWorkStatus.DONE);
        Instant afterEnd = draft.getIssueEndAt().plusSeconds(1);
        budgetWorker(afterEnd).runDue();
        budgetWorker(afterEnd.plusSeconds(1)).runDue();
        assertThat(reservation(draft.getBudgetReservationId()).releasedHp()).isEqualTo(7_500);
        assertThat(budgetWorkRepository.findByCouponIdAndKindOrderByCreatedAtAsc(couponId, BudgetWorkKind.RELEASE))
                .singleElement().satisfies(release -> {
                    assertThat(release.getWorkId()).isEqualTo("coupon-" + couponId + "-release-unissued-v1");
                    assertThat(release.getAmount()).isEqualTo(7_500);
                    assertThat(release.getTarget()).isEqualTo("UNISSUED:5");
                    assertThat(release.getStatus()).isEqualTo(BudgetWorkStatus.DONE);
                });
    }

    private CouponOpenUseCase budgetOpener(Instant now, CouponIssueStore issueStore) {
        org.springframework.beans.factory.support.StaticListableBeanFactory beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("budget", promotionBudget);
        return new CouponOpenUseCase(new CouponSupport(couponRepository, lectureCatalog, issueStore),
                budgetWorkRepository, budgetWorker(now), issueStore, capacitySupport,
                beans.getBeanProvider(PromotionBudget.class), redisProperties, budgetTransactionManager,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    private static void assertBudgetHttpOutsideTransaction() {
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private static void awaitBudgetGate(CountDownLatch gate) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("예산 HTTP 제어 latch 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void 플랫폼_미사용_만료는_상태와_해제_작업을_함께_기록하고_재실행해도_한번이다() {
        Instant now = Instant.now();
        long couponId = budgetCoupon(now, true);
        long memberId = memberCouponRepository.saveAndFlush(MemberCoupon.issue(MEMBER_A, couponId, "budget-expiry",
                now.minusSeconds(60), now.minusSeconds(1), now)).getId();

        memberCouponExpiryUseCase.expireDue();
        memberCouponExpiryUseCase.expireDue();

        assertThat(memberCouponRepository.findStateById(memberId).orElseThrow().status()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(budgetWorkRepository.findAll()).singleElement().satisfies(work -> {
            assertThat(work.getAmount()).isEqualTo(1_500);
            assertThat(work.getStatus()).isEqualTo(com.hapbang.coupon.domain.BudgetWorkStatus.PENDING);
            assertThat(work.getTarget()).isEqualTo("MEMBER_COUPON:" + memberId);
        });
        budgetWorker(now).runDue();
        budgetWorker(now.plusSeconds(1)).runDue();
        assertThat(reservation(couponRepository.findById(couponId).orElseThrow().getBudgetReservationId()).releasedHp())
                .isEqualTo(1_500);
        assertThat(budgetWorkRepository.findAll()).singleElement().extracting(BudgetWork::getStatus)
                .isEqualTo(BudgetWorkStatus.DONE);
    }

    @Test
    void 사용후_만료는_M에서_F를_남기고_취소복구가_뒤에_와도_누적_M만_해제하며_불명_작업은_같은_ID로_재시도한다() {
        Instant now = Instant.now();
        long couponId = budgetCoupon(now, true);
        long memberId = memberCouponRepository.saveAndFlush(MemberCoupon.issue(MEMBER_A, couponId, "budget-used",
                now.minusSeconds(120), now.minusSeconds(1), now)).getId();
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(budgetTransactionManager);
        tx.executeWithoutResult(status -> {
            memberCouponRepository.reserve(memberId, MEMBER_A, 0, 901L, now.minusSeconds(60));
            memberCouponRepository.markUsed(memberId, 1, now.minusSeconds(60));
            com.hapbang.coupon.domain.CouponUsage usage = com.hapbang.coupon.domain.CouponUsage.reserve(MEMBER_A, 900L,
                    new com.hapbang.coupon.domain.usage.UsageItem(901L, LECTURE_ID, SELLER_ID, PRICE),
                    new com.hapbang.coupon.domain.usage.AppliedCoupon(memberId, couponId,
                            com.hapbang.coupon.domain.CouponType.PLATFORM, 1, PRICE, 400, 400, 0, 0, 1),
                    "budget-reserve", now.minusSeconds(60));
            usage.applyPaid("budget-paid", now.minusSeconds(60), MemberCouponStatus.RESERVED, 1, now.minusSeconds(60));
            couponUsageRepository.saveAndFlush(usage);
        });
        holdPlatform(couponId, memberId, 400L);
        budgetReleaseRecorder.recordDue(memberId, now);
        budgetReleaseRecorder.recordDue(memberId, now);
        assertThat(budgetWorkRepository.findAll()).singleElement().extracting(com.hapbang.coupon.domain.BudgetWork::getAmount)
                .isEqualTo(1_100L);
        assertThat(memberCouponRepository.findBudgetState(memberId).orElseThrow().checkedStatus()).isEqualTo(MemberCouponStatus.USED);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(couponId, now, null, 0, org.springframework.data.domain.Limit.of(10)))
                .isEmpty();
        com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent canceled =
                com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent.of(900L, 901L, MEMBER_A,
                        List.of(new com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent.Usage(memberId, 1)),
                        "budget-cancel", List.of(new com.hapbang.shared.lecture.event.OrderItemCancelCompletedEvent.BudgetRestoration(
                                couponId, memberId, 1, 400)), now);
        releasePlatform();
        assertThat(couponOrderConsumerUseCase.consume(canceled))
                .isEqualTo(com.hapbang.coupon.app.CouponOrderConsumerUseCase.Result.APPLIED);
        assertThat(memberCouponRepository.findStateById(memberId).orElseThrow().status()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(budgetWorkRepository.findAll()).hasSize(2);
        assertThat(budgetWorkRepository.findAll().stream().mapToLong(com.hapbang.coupon.domain.BudgetWork::getAmount).sum())
                .isEqualTo(1_500);

        List<BudgetWork> releases = budgetWorkRepository.findAll();
        for (BudgetWork release : releases) {
            billing.dropNextResponse("/releases");
            budgetWorker(now).execute(release.getWorkId());
        }
        budgetWorker(now.plusSeconds(1)).runDue();
        assertThat(budgetWorkRepository.findAll()).allSatisfy(work -> {
            assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
            assertThat(work.getAttempt()).isEqualTo(1);
        });
        budgetWorker(now.plusSeconds(61)).runDue();
        assertThat(budgetWorkRepository.findAll()).allSatisfy(work -> {
            assertThat(work.getStatus()).isEqualTo(BudgetWorkStatus.DONE);
            assertThat(work.getAttempt()).isEqualTo(2);
            assertThat(promotionBudget.status(work.getWorkId()).work().workId()).isEqualTo(work.getWorkId());
        });
        assertThat(reservation(couponRepository.findById(couponId).orElseThrow().getBudgetReservationId()).releasedHp())
                .isEqualTo(1_500);
    }

    @Test
    void 예산_예약이_누락된_플랫폼만_롤백하고_같은_묶음과_다음_묶음의_정상_쿠폰은_만료한다() {
        Instant now = Instant.now();
        long badCoupon = budgetCoupon(now, false);
        long platformCoupon = budgetCoupon(now, true);
        long sellerCoupon = couponRepository.saveAndFlush(Coupon.createSeller(SELLER_ID,
                com.hapbang.coupon.domain.CouponFixture.lectureRate().build(), now)).getId();
        long bad = memberCouponRepository.saveAndFlush(MemberCoupon.issue(MEMBER_A, badCoupon, "bad-budget",
                now.minusSeconds(60), now.minusSeconds(30), now)).getId();
        long seller = memberCouponRepository.saveAndFlush(MemberCoupon.issue(MEMBER_A, sellerCoupon, "good-seller",
                now.minusSeconds(60), now.minusSeconds(20), now)).getId();
        long platform = memberCouponRepository.saveAndFlush(MemberCoupon.issue(MEMBER_A, platformCoupon, "good-platform",
                now.minusSeconds(60), now.minusSeconds(10), now)).getId();
        var metrics = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var expiry = new com.hapbang.coupon.app.MemberCouponExpiryUseCase(budgetReleaseRecorder, memberCouponRepository,
                new com.hapbang.coupon.config.CouponExpiryProperties(null, 2),
                metrics.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), budgetTransactionManager,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));

        assertThat(expiry.expireDue().expired()).isEqualTo(2);
        assertThat(memberCouponRepository.findStateById(bad).orElseThrow().status()).isEqualTo(MemberCouponStatus.AVAILABLE);
        assertThat(memberCouponRepository.findStateById(seller).orElseThrow().status()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(memberCouponRepository.findStateById(platform).orElseThrow().status()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(budgetWorkRepository.findAll()).singleElement().extracting(BudgetWork::getCouponId).isEqualTo(platformCoupon);
        assertThat(expiry.expireDue().expired()).isZero();
        reserveBudget(badCoupon, now);
        assertThat(expiry.expireDue().expired()).isEqualTo(1);
        assertThat(memberCouponRepository.findStateById(bad).orElseThrow().status()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(budgetWorkRepository.findAll()).hasSize(2);
    }

    @Test
    void M과_F가_같아_해제액이_0인_USED도_완료로_제외하며_EXPIRED복원은_다시_선택한다() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        long couponId = budgetCoupon(now, true);
        long id = memberCouponRepository.saveAndFlush(MemberCoupon.issue(MEMBER_A, couponId, "full-used",
                now.minusSeconds(60), now, now)).getId();
        var tx = new org.springframework.transaction.support.TransactionTemplate(budgetTransactionManager);
        tx.executeWithoutResult(status -> {
            memberCouponRepository.reserve(id, MEMBER_A, 0, 901L, now.minusSeconds(1));
            memberCouponRepository.markUsed(id, 1, now);
            var usage = com.hapbang.coupon.domain.CouponUsage.reserve(MEMBER_A, 900L,
                    new com.hapbang.coupon.domain.usage.UsageItem(901L, LECTURE_ID, SELLER_ID, PRICE),
                    new com.hapbang.coupon.domain.usage.AppliedCoupon(id, couponId,
                            com.hapbang.coupon.domain.CouponType.PLATFORM, 1, PRICE, 1_500, 1_500, 0, 0, 1),
                    "budget-full-used", now.minusSeconds(1));
            usage.applyPaid("budget-full-paid", now, MemberCouponStatus.RESERVED, 1, now);
            couponUsageRepository.saveAndFlush(usage);
        });

        budgetReleaseRecorder.recordDue(id, now);
        assertThat(memberCouponRepository.findBudgetState(id).orElseThrow()).satisfies(state -> {
            assertThat(state.plannedHp()).isZero();
            assertThat(state.checkedStatus()).isEqualTo(MemberCouponStatus.USED);
        });
        assertThat(budgetWorkRepository.findAll()).isEmpty();
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(couponId, now, null, 0, org.springframework.data.domain.Limit.of(10)))
                .isEmpty();

        holdPlatform(couponId, id, 1_500L);
        releasePlatform();
        // 이전 writer처럼 완료 표식을 건드리지 않고 상태만 EXPIRED로 복원해도 다시 수집한다.
        tx.executeWithoutResult(status -> memberCouponRepository.restore(id, 1, now));
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(couponId, now, null, 0, org.springframework.data.domain.Limit.of(10)))
                .extracting(com.hapbang.coupon.out.MemberCouponExpiryTarget::id).containsExactly(id);
        budgetReleaseRecorder.recordDue(id, now);
        assertThat(memberCouponRepository.findBudgetState(id).orElseThrow().checkedStatus()).isEqualTo(MemberCouponStatus.EXPIRED);
        assertThat(budgetWorkRepository.findAll()).singleElement().extracting(BudgetWork::getAmount).isEqualTo(1_500L);
        assertThat(memberCouponRepository.findExpiredPlatformBudgetTargets(couponId, now, null, 0, org.springframework.data.domain.Limit.of(10)))
                .isEmpty();
    }

    @Test
    void 발급정리된_CLOSED_쿠폰은_미발급_수량의_예산만_한번_등록한다() {
        Instant now = Instant.now();
        long couponId = budgetCoupon(now, true);
        new org.springframework.transaction.support.TransactionTemplate(budgetTransactionManager).executeWithoutResult(status -> {
            Coupon coupon = couponRepository.findById(couponId).orElseThrow();
            coupon.markRedisReady(now);
            coupon.open(now);
            coupon.close(now);
        });
        budgetJdbc.update("update coupon.coupon set issued_count = 2 where id = ?", couponId);
        budgetReleaseRecorder.recordUnissued(couponId, now);
        budgetReleaseRecorder.recordUnissued(couponId, now);
        assertThat(budgetWorkRepository.findAll()).singleElement().satisfies(work -> {
            assertThat(work.getAmount()).isEqualTo(4_500L);
            assertThat(work.getTarget()).isEqualTo("UNISSUED:3");
        });
        budgetWorker(now).runDue();
        budgetWorker(now.plusSeconds(1)).runDue();
        assertThat(reservation(couponRepository.findById(couponId).orElseThrow().getBudgetReservationId()))
                .satisfies(reservation -> {
                    assertThat(reservation.releasedHp()).isEqualTo(4_500);
                    assertThat(reservation.remainingHp()).isEqualTo(3_000);
                });
    }

    @Test
    void lease가_끝나_새_시도가_선점하면_먼저_시작한_시도의_늦은_결과는_반영하지_않는다() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        long couponId = budgetCoupon(now, true);
        long memberId = memberCouponRepository.saveAndFlush(MemberCoupon.issue(MEMBER_A, couponId, "budget-fence",
                now.minusSeconds(60), now.minusSeconds(1), now)).getId();
        memberCouponExpiryUseCase.expireDue();
        BudgetWork work = budgetWorkRepository.findAll().getFirst();
        assertThat(promotionBudget.release(work.getWorkId(), work.getReservationId(), work.getAmount(), work.getTarget(), work.getReason()).outcome())
                .isEqualTo(Outcome.SUCCEEDED);
        billing.afterNextResponse("/works/" + work.getWorkId(), () -> {
            // GET 전송 계층의 재시도도 응답을 잃게 한다. 새 시도가 끝난 뒤 옛 시도의 응답만 정상 전달한다.
            billing.dropResponses(100);
            try {
                budgetWorker(now.plusSeconds(61)).runDue();
            } finally {
                billing.dropResponses(0);
            }
        });
        budgetWorker(now).runDue();
        assertThat(budgetWorkRepository.findById(work.getWorkId()).orElseThrow()).satisfies(current -> {
            assertThat(current.getAttempt()).isEqualTo(2);
            assertThat(current.getStatus()).isEqualTo(BudgetWorkStatus.PENDING);
            assertThat(current.getLeaseUntil()).isEqualTo(now.plusSeconds(121));
        });
        assertThat(memberCouponRepository.findBudgetState(memberId).orElseThrow().plannedHp()).isEqualTo(1_500);
        assertThat(reservation(work.getReservationId()).releasedHp()).isEqualTo(1_500);
    }

    private long budgetCoupon(Instant now, boolean reserved) {
        Coupon coupon = Coupon.createPlatform(ADMIN_ID,
                com.hapbang.coupon.domain.CouponFixture.anyLectureAmount().quantity(5)
                        .issuePeriod(now.minusSeconds(3600), now.plusSeconds(3600)).build(), now);
        long id = couponRepository.saveAndFlush(coupon).getId();
        if (reserved) reserveBudget(id, now);
        return id;
    }

    private com.hapbang.coupon.app.CouponBudgetWorkUseCase budgetWorker(Instant now) {
        org.springframework.beans.factory.support.StaticListableBeanFactory beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("budget", promotionBudget);
        return new com.hapbang.coupon.app.CouponBudgetWorkUseCase(budgetWorkRepository, couponRepository,
                memberCouponRepository, budgetReleaseRecorder, beans.getBeanProvider(com.hapbang.coupon.app.port.PromotionBudget.class),
                new com.hapbang.coupon.config.CouponBudgetWorkProperties(null, java.time.Duration.ofSeconds(60), 100),
                budgetTransactionManager, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
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
    void Redis가_멈춰도_계산_예약_해제가_되고_발급_접수는_수_초_안에_503이다() throws Exception {
        givenLecture();
        long sellerCoupon = issue(MEMBER_A, saveSellerCoupon());
        long platformCoupon = issue(MEMBER_A, savePlatformCoupon());
        long issueCoupon = saveSellerCoupon();
        // 운영처럼 이미 맺은 연결에서 명령이 멈추는 경우를 보려고, 멈추기 전에 명령을 하나 보내 연결을 맺어 둔다
        stringRedisTemplate.hasKey("coupon-usage-e2e:warm-up");
        RedisTestcontainersConfig.withRedisPaused(() -> unchecked(() -> {
            String calculated = calculate(MEMBER_A, "null")
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> basis = JsonPath.read(calculated, "$.data.items[0].coupons");
            reserve(MEMBER_A, 9201L, 92011L, "order-9201:reserve:1", basisJson(basis))
                    .andExpect(status().isOk());
            release(9201L, "order-9201:release:1", List.of(sellerCoupon, platformCoupon))
                    .andExpect(status().isOk());

            // 발급 접수는 Redis가 필요하다. 응답 없는 Redis를 명령 제한 시간(2초)에 끊어 503으로 끝내야 요청 스레드가 오래 묶이지 않는다.
            // 매진 표시 조회(실패하면 Lua에 맡긴다)와 접수 Lua가 차례로 끊겨 최대 제한 시간의 두 배(약 4초)다. 예전 기본값이면 120초다
            long started = System.nanoTime();
            mockMvc.perform(post("/api/v1/coupons/{couponId}/issue-requests", issueCoupon)
                            .with(user(String.valueOf(MEMBER_A)).roles("USER")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"issueRequestId\":\"redis-down-1\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("COUPON_ISSUE_STORE_UNAVAILABLE"));
            // 확인할 것은 기본값(60초 × 2)이 아니라는 것이다. CI 기계 차이로 흔들리지 않게 상한은 넉넉히 둔다
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        }));
    }

    /** {@code withRedisPaused}는 Runnable을 받아 검사 예외를 감싼다 */
    private static void unchecked(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void fundPromotion(long amount) {
        org.springframework.web.client.RestClient.create(billing.directBaseUrl()).post()
                .uri("/api/v1/settlements/admin/promotion-wallet/fundings")
                .header("X-User-Id", String.valueOf(ADMIN_ID)).header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fundingWorkId", "coupon-budget-" + java.util.UUID.randomUUID(), "amountHp", amount,
                        "sourceType", "ADMIN_GRANT", "sourceRef", "coupon-budget-e2e"))
                .retrieve().toBodilessEntity();
    }

    private void reserveBudget(long couponId, Instant now) {
        Coupon coupon = couponRepository.findById(couponId).orElseThrow();
        fundPromotion(coupon.budgetAmount());
        BudgetResult result = promotionBudget.reserve(coupon.budgetReserveWorkId(), couponId, coupon.getPolicyVersion(), coupon.budgetAmount());
        assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
        new org.springframework.transaction.support.TransactionTemplate(budgetTransactionManager).executeWithoutResult(status ->
                couponRepository.findById(couponId).orElseThrow().recordBudgetReserved(result.reservationId(), now));
    }

    private com.hapbang.shared.billing.dto.PromotionBudgetReservationDto reservation(String id) {
        var result = billing.client().getBudgetReservation(id);
        assertThat(result).isInstanceOf(com.hapbang.shared.billing.out.BillingCallResult.Succeeded.class);
        return ((com.hapbang.shared.billing.out.BillingCallResult.Succeeded<com.hapbang.shared.billing.dto.PromotionBudgetReservationDto>) result).value();
    }

    private void holdPlatform(long couponId, long memberCouponId, long amount) {
        billing.wallet(MEMBER_A, 0L, 0L);
        assertThat(billing.client().hold(new com.hapbang.shared.billing.dto.HoldCommand(MEMBER_A,
                com.hapbang.shared.billing.domain.BillingReferenceType.LECTURE_ORDER_ITEM, 901L, 0L,
                List.of(new com.hapbang.shared.billing.dto.HoldPromotionDto(couponId, memberCouponId, 1L, amount)))))
                .isInstanceOf(com.hapbang.shared.billing.out.BillingCallResult.Succeeded.class);
    }

    private void releasePlatform() {
        assertThat(billing.client().release(com.hapbang.shared.billing.dto.ReleaseCommand.full(MEMBER_A,
                com.hapbang.shared.billing.domain.BillingReferenceType.LECTURE_ORDER_ITEM, 901L, "budget-cancel")))
                .isInstanceOf(com.hapbang.shared.billing.out.BillingCallResult.Succeeded.class);
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
        Instant now = Instant.now();
        long id = couponRepository.saveAndFlush(Coupon.createPlatform(ADMIN_ID, terms(CouponTargetType.ANY_LECTURE, null, null,
                DiscountType.AMOUNT, 1_500), now)).getId();
        reserveBudget(id, now);
        return id;
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
