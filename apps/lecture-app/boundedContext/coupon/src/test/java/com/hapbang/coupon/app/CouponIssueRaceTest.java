package com.hapbang.coupon.app;

import static com.hapbang.coupon.domain.CouponFixture.SELLER_ID;
import static com.hapbang.coupon.domain.CouponFixture.lectureRate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.LectureCatalog;
import com.hapbang.coupon.app.port.PromotionBudgetPort;
import com.hapbang.coupon.domain.Coupon;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.out.BudgetWorkRepository;
import com.hapbang.coupon.out.CouponRepository;
import com.hapbang.coupon.out.redis.CouponRedisKeys;
import com.hapbang.coupon.out.redis.CouponRedisProperties;
import com.hapbang.coupon.out.redis.RedisCouponIssueStore;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 발급 열기·막기가 Redis 응답 유실·늦은 도착과 겹칠 때 DB와 Redis issue-control이 안전하고 같은 상태로 끝나는지 본다.
 * Redis는 실제 저장소(Testcontainers)를 spy로 감싸 호출 사이에 다른 요청을 끼워 넣고, DB는 version을 흉내 내는 대역이다.
 * 접수 여부는 issue.lua가 보는 issue-control status(OPEN만 접수)로 판단한다.
 */
@SpringJUnitConfig(CouponIssueRaceTest.Config.class)
class CouponIssueRaceTest {

    @Autowired
    private RedisCouponIssueStore redisStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final CouponRepository couponRepository = mock();
    private CouponIssueStore store;
    private CouponOpenUseCase openUseCase;
    private CouponOperateUseCase operateUseCase;
    private Coupon coupon;
    private long couponId;

    @BeforeEach
    void setUp() {
        store = spy(redisStore);
        CouponVersionFake db = new CouponVersionFake(couponRepository);
        Clock clock = Clock.systemUTC();
        CouponSupport support = new CouponSupport(couponRepository, mock(LectureCatalog.class), store);
        @SuppressWarnings("unchecked")
        ObjectProvider<PromotionBudgetPort> budgetPortProvider = mock();
        openUseCase = new CouponOpenUseCase(support, mock(BudgetWorkRepository.class), store, budgetPortProvider,
                new CouponRedisProperties(null), db.transactionManager(), clock);
        operateUseCase = new CouponOperateUseCase(support, store, db.transactionManager(), clock);

        Instant now = clock.instant();
        couponId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        coupon = Coupon.createSeller(SELLER_ID,
                lectureRate().issuePeriod(now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1))).build(), now);
        ReflectionTestUtils.setField(coupon, "id", couponId);
        coupon.submitReview(now);
        db.given(coupon);
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of(CouponRedisKeys.issueControl(couponId), CouponRedisKeys.stock(couponId)));
    }

    @Test
    void 승인의_Redis_응답을_잃어도_DB가_OPEN이_아닌데_접수가_열리지_않고_다시_승인하면_열린다() {
        // 1) 준비는 Redis에 썼지만 응답을 잃었다: DB는 REVIEW, Redis는 준비만 됐고 접수 불가
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new CouponIssueStoreUnavailableException("응답 유실", null);
        }).doCallRealMethod().when(store).prepare(any());
        assertThatThrownBy(() -> openUseCase.approve(couponId))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.REVIEW);
        assertThat(redisStatus()).isEqualTo("READY");

        // 2) DB OPEN 커밋 뒤 접수 열기는 Redis에 썼지만 응답을 잃었다: DB OPEN, Redis OPEN(일치)
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new CouponIssueStoreUnavailableException("응답 유실", null);
        }).doCallRealMethod().when(store).allowIssue(anyLong(), anyLong());
        assertThatThrownBy(() -> openUseCase.approve(couponId))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.OPEN);
        assertThat(redisStatus()).isEqualTo("OPEN");

        // 3) 같은 요청을 다시 보내면 version을 올려 다시 열고 성공한다
        assertThat(openUseCase.approve(couponId).status()).isEqualTo(CouponStatus.OPEN);
        assertThat(redisStatus()).isEqualTo("OPEN");
        assertThat(redisFence()).isEqualTo(coupon.getVersion());
    }

    @Test
    void 재개의_접수_열기가_그_뒤의_중단보다_늦게_도착하면_무시되어_DB와_Redis가_모두_SUSPENDED다() {
        openUseCase.approve(couponId);
        operateUseCase.suspend(couponId);
        // 재개가 DB OPEN을 커밋한 뒤, Redis 접수 열기가 도착하기 전에 중단이 끝난다. 늦게 도착한 열기의 응답도 잃는다
        doAnswer(invocation -> {
            operateUseCase.suspend(couponId);
            invocation.callRealMethod();
            throw new CouponIssueStoreUnavailableException("응답 유실", null);
        }).doCallRealMethod().when(store).allowIssue(anyLong(), anyLong());

        assertThatThrownBy(() -> operateUseCase.resume(couponId))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.SUSPENDED);
        assertThat(redisStatus()).isEqualTo("SUSPENDED");
    }

    @Test
    void 중단의_두_번째_막기가_그_뒤의_재개보다_늦게_도착하면_무시되어_DB와_Redis가_모두_OPEN이다() {
        openUseCase.approve(couponId);
        // 중단이 DB SUSPENDED를 커밋한 뒤, 두 번째 막기가 도착하기 전에 재개가 끝난다
        doCallRealMethod().doAnswer(invocation -> {
            operateUseCase.resume(couponId);
            return invocation.callRealMethod();
        }).when(store).block(anyLong(), anyString(), anyLong());

        operateUseCase.suspend(couponId);

        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.OPEN);
        assertThat(redisStatus()).isEqualTo("OPEN");
    }

    @Test
    void 중단의_첫_막기만_반영되고_DB_전이가_실패해도_재개를_다시_부르면_version을_올려_접수를_연다() {
        openUseCase.approve(couponId);
        // 중단이 지금 version으로 Redis를 막은 뒤 DB 전이에 실패했다: DB OPEN, Redis SUSPENDED(같은 version)
        store.block(couponId, "SUSPENDED", coupon.getVersion());
        assertThat(redisStatus()).isEqualTo("SUSPENDED");

        assertThat(operateUseCase.resume(couponId).status()).isEqualTo(CouponStatus.OPEN);

        assertThat(redisStatus()).isEqualTo("OPEN");
    }

    private String redisStatus() {
        return (String) redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "status");
    }

    private long redisFence() {
        return Long.parseLong((String) redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "fence"));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RedisTestcontainersConfig.class, RedisCouponIssueStore.class})
    @ImportAutoConfiguration(DataRedisAutoConfiguration.class)
    static class Config {
    }
}
