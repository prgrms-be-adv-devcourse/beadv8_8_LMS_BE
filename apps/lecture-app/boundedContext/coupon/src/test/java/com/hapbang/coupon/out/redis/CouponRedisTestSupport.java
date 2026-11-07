package com.hapbang.coupon.out.redis;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.hapbang.coupon.app.port.CouponIssueStore.IssueAttempt;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueControl;
import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 실제 Redis(Testcontainers)에 쿠폰 저장소만 올린다. 테스트끼리 Redis를 같이 쓰므로 쿠폰 ID·Stream key를 테스트마다 새로 만들고
 * 자기가 만든 key만 지운다.
 */
@SpringJUnitConfig(CouponRedisTestSupport.Config.class)
abstract class CouponRedisTestSupport {

    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    static final Instant START = NOW.minus(Duration.ofHours(1));
    static final Instant END = NOW.plus(Duration.ofHours(1));
    static final Instant KEYS_EXPIRE_AT = END.plus(Duration.ofDays(7));
    static final int EXPIRY_DAYS = 30;

    @Autowired
    RedisCouponIssueStore store;

    @Autowired
    StringRedisTemplate redisTemplate;

    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    long newCouponId() {
        long couponId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        createdKeys.addAll(List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId),
                CouponRedisKeys.soldOut(couponId)));
        return couponId;
    }

    String newStreamKey() {
        String key = "coupon-test:stream:" + UUID.randomUUID();
        createdKeys.add(key);
        return key;
    }

    /** fence 0(새 쿠폰의 DB version), 접수 기준 30일 만료로 준비하는 값 */
    static IssueControl control(long couponId, long epoch, long stock) {
        return new IssueControl(couponId, epoch, stock, START, END, KEYS_EXPIRE_AT, 0, ExpiryType.RELATIVE,
                EXPIRY_DAYS, null);
    }

    /** 고정 시각 만료로 준비하는 값 */
    static IssueControl fixedControl(long couponId, long epoch, long stock, Instant fixedExpiresAt) {
        return new IssueControl(couponId, epoch, stock, START, END, KEYS_EXPIRE_AT, 0, ExpiryType.FIXED, null,
                fixedExpiresAt);
    }

    /** 준비한 뒤 DB OPEN 커밋(version 1)을 흉내 내 접수를 연다 */
    void open(IssueControl control) {
        store.prepare(control);
        store.allowIssue(control.couponId(), control.fence() + 1);
    }

    String controlStatus(long couponId) {
        return (String) redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "status");
    }

    static IssueAttempt attempt(long couponId, long memberId, String requestId) {
        return attemptAt(couponId, memberId, requestId, NOW);
    }

    static IssueAttempt attemptAt(long couponId, long memberId, String requestId, Instant issuedAt) {
        return new IssueAttempt(couponId, memberId, requestId, issuedAt);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RedisTestcontainersConfig.class, RedisCouponIssueStore.class})
    @ImportAutoConfiguration(DataRedisAutoConfiguration.class)
    static class Config {
    }
}
