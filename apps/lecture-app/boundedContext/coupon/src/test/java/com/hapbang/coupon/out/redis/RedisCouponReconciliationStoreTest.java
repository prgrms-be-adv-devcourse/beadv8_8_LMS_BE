package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.hapbang.coupon.app.port.CouponReconciliationStore.RepairResult;
import com.hapbang.coupon.app.port.CouponReconciliationStore.Snapshot;
import com.hapbang.coupon.domain.IssueHoldReason;
import com.hapbang.coupon.domain.IssueReconciliationRule.MissingUser;
import com.hapbang.coupon.domain.IssueReconciliationRule.SoldOutRepair;
import com.hapbang.testsupport.RedisTestcontainersConfig;

// reconcile-*.lua를 실제 Redis에서 본다. 쿠폰 ID를 테스트마다 새로 만들고 자기 key만 지운다
@SpringJUnitConfig(RedisCouponReconciliationStoreTest.Config.class)
class RedisCouponReconciliationStoreTest {

    private static final long KEYS_EXPIRE_AT = Instant.now().plus(Duration.ofDays(1)).toEpochMilli();

    @Autowired
    private RedisCouponReconciliationStore store;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    void 재고_매진_회원_요청_기록과_issue_control을_한_번에_읽는다() {
        long couponId = preparedCoupon("OPEN");
        redisTemplate.opsForValue().set(CouponRedisKeys.stock(couponId), "8");
        redisTemplate.opsForSet().add(CouponRedisKeys.users(couponId), "1", "2");
        redisTemplate.opsForHash().put(CouponRedisKeys.requests(couponId), "r1", "1|1000|ACCEPTED|2000|PUBLISHED");
        redisTemplate.opsForHash().put(CouponRedisKeys.issueControl(couponId), "hold", "BACKLOG");

        Snapshot snapshot = store.snapshot(couponId);

        assertThat(snapshot.controlExists()).isTrue();
        assertThat(snapshot.ready()).isTrue();
        assertThat(snapshot.status()).isEqualTo("OPEN");
        assertThat(snapshot.epoch()).isEqualTo(3L);
        assertThat(snapshot.hold()).isEqualTo(IssueHoldReason.BACKLOG);
        assertThat(snapshot.stock()).isEqualTo(8L);
        assertThat(snapshot.soldOut()).isFalse();
        assertThat(snapshot.users()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(snapshot.requests()).containsExactly(Map.entry("r1", "1|1000|ACCEPTED|2000|PUBLISHED"));
    }

    @Test
    void 기록이_없으면_빈_스냅숏이다() {
        Snapshot snapshot = store.snapshot(newCouponId());

        assertThat(snapshot.controlExists()).isFalse();
        assertThat(snapshot.stock()).isNull();
        assertThat(snapshot.users()).isEmpty();
        assertThat(snapshot.requests()).isEmpty();
    }

    @Test
    void 보류는_더_무거운_사유만_덮고_자기_사유일_때만_풀며_issue_control을_만들지_않는다() {
        long couponId = preparedCoupon("OPEN");

        assertThat(store.hold(couponId, IssueHoldReason.BACKLOG)).isTrue();
        assertThat(store.hold(couponId, IssueHoldReason.BACKLOG)).isFalse();
        assertThat(store.hold(couponId, IssueHoldReason.RECONCILE)).isTrue();
        assertThat(store.hold(couponId, IssueHoldReason.BACKLOG)).isFalse();
        assertThat(store.releaseHold(couponId, IssueHoldReason.BACKLOG)).isFalse();
        assertThat(hold(couponId)).isEqualTo("RECONCILE");
        assertThat(store.readHold(couponId)).isEqualTo(IssueHoldReason.RECONCILE);
        assertThat(store.releaseHold(couponId, IssueHoldReason.RECONCILE)).isTrue();
        assertThat(hold(couponId)).isNull();
        assertThat(store.readHold(couponId)).isNull();

        long missing = newCouponId();
        assertThat(store.readHold(missing)).isNull();
        assertThat(store.hold(missing, IssueHoldReason.RECONCILE)).isFalse();
        assertThat(redisTemplate.hasKey(CouponRedisKeys.issueControl(missing))).isFalse();
    }

    @Test
    void 매진_표시는_그때의_재고로_고치고_회원은_보상되지_않은_요청과_확정_발급만_넣는다() {
        long couponId = preparedCoupon("OPEN");
        redisTemplate.opsForValue().set(CouponRedisKeys.stock(couponId), "0");
        redisTemplate.opsForHash().put(CouponRedisKeys.requests(couponId), "live", "11|1000|ACCEPTED|2000");
        redisTemplate.opsForHash().put(CouponRedisKeys.requests(couponId), "comp", "12|1000|ACCEPTED|2000|COMPENSATED");

        RepairResult result = store.repair(couponId, SoldOutRepair.SET, List.of(new MissingUser(10, null),
                new MissingUser(11, "live"), new MissingUser(12, "comp"), new MissingUser(13, "gone")));

        assertThat(result).isEqualTo(new RepairResult(1, 2));
        assertThat(redisTemplate.opsForSet().members(CouponRedisKeys.users(couponId))).containsExactlyInAnyOrder("10",
                "11");
        assertThat(redisTemplate.getExpire(CouponRedisKeys.users(couponId))).isPositive();
        assertThat(redisTemplate.hasKey(CouponRedisKeys.soldOut(couponId))).isTrue();

        // 재고가 0이 아니면 매진 표시를 쓰지 않고, 재고가 남았으면 지운다
        redisTemplate.opsForValue().set(CouponRedisKeys.stock(couponId), "2");
        assertThat(store.repair(couponId, SoldOutRepair.CLEAR, List.of()).soldOutChange()).isEqualTo(-1);
        assertThat(store.repair(couponId, SoldOutRepair.SET, List.of()).soldOutChange()).isZero();
        assertThat(redisTemplate.hasKey(CouponRedisKeys.soldOut(couponId))).isFalse();
    }

    @Test
    void 정리는_요청_기록이_남았거나_접수가_열려_있으면_하지_않고_아니면_쿠폰_key를_모두_지운다() {
        long couponId = preparedCoupon("OPEN");
        redisTemplate.opsForValue().set(CouponRedisKeys.stock(couponId), "0");
        redisTemplate.opsForSet().add(CouponRedisKeys.users(couponId), "1");
        redisTemplate.opsForHash().put(CouponRedisKeys.requests(couponId), "r1", "1|1000|ACCEPTED|2000");

        assertThat(store.cleanUp(couponId)).isFalse();
        redisTemplate.opsForHash().put(CouponRedisKeys.issueControl(couponId), "status", "CLOSED");
        assertThat(store.cleanUp(couponId)).isFalse();

        assertThat(store.removeRequests(couponId, List.of("r1", "unknown"))).isEqualTo(1);
        assertThat(store.cleanUp(couponId)).isTrue();

        assertThat(redisTemplate.countExistingKeys(List.of(CouponRedisKeys.issueControl(couponId),
                CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId), CouponRedisKeys.requests(couponId))))
                .isZero();
    }

    private long preparedCoupon(String status) {
        long couponId = newCouponId();
        redisTemplate.opsForHash().putAll(CouponRedisKeys.issueControl(couponId),
                Map.of("ready", "1", "status", status, "epoch", "3", "keysExpireAt", String.valueOf(KEYS_EXPIRE_AT)));
        return couponId;
    }

    private String hold(long couponId) {
        return (String) redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "hold");
    }

    private long newCouponId() {
        long couponId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        createdKeys.addAll(List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId),
                CouponRedisKeys.soldOut(couponId)));
        return couponId;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RedisTestcontainersConfig.class, RedisCouponReconciliationStore.class})
    @ImportAutoConfiguration(DataRedisAutoConfiguration.class)
    static class Config {
    }
}
