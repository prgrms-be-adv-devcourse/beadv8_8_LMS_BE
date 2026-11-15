package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;

import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;

/** key 만료 연장(extend-keys.lua): 늦출 때만 바꾸고 줄이지 않으며, 없는 key를 만들지 않는다. 실제 Redis로 확인한다 */
class ExtendKeysLuaTest extends CouponRedisTestSupport {

    private static final RedisScript<Long> PEXPIRETIME =
            RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class);

    @Test
    @DisplayName("지금 만료보다 늦은 시각이면 있는 key의 만료와 issue-control의 keysExpireAt을 그 시각으로 늦춘다")
    void extendsLater() {
        long couponId = newCouponId();
        open(control(couponId, 1, 10));
        store.tryIssue(attempt(couponId, 7, "req-1"));
        Instant later = KEYS_EXPIRE_AT.plus(Duration.ofDays(3));

        int extended = store.extendKeysExpiry(couponId, later);

        // 매진 표시는 없으므로 issue-control·stock·users·requests 4개
        assertThat(extended).isEqualTo(4);
        for (String key : List.of(CouponRedisKeys.issueControl(couponId), CouponRedisKeys.stock(couponId),
                CouponRedisKeys.users(couponId), CouponRedisKeys.requests(couponId))) {
            assertThat(expireTime(key)).as(key).isEqualTo(later.toEpochMilli());
        }
        assertThat(keysExpireAtField(couponId)).isEqualTo(String.valueOf(later.toEpochMilli()));
        assertThat(redisTemplate.hasKey(CouponRedisKeys.soldOut(couponId))).isFalse();
    }

    @Test
    @DisplayName("지금 만료보다 이른 시각이면 만료도 keysExpireAt도 줄이지 않는다")
    void neverShortens() {
        long couponId = newCouponId();
        open(control(couponId, 1, 10));
        store.tryIssue(attempt(couponId, 7, "req-1"));

        int extended = store.extendKeysExpiry(couponId, KEYS_EXPIRE_AT.minus(Duration.ofDays(1)));

        assertThat(extended).isZero();
        assertThat(expireTime(CouponRedisKeys.requests(couponId))).isEqualTo(KEYS_EXPIRE_AT.toEpochMilli());
        assertThat(expireTime(CouponRedisKeys.issueControl(couponId))).isEqualTo(KEYS_EXPIRE_AT.toEpochMilli());
        assertThat(keysExpireAtField(couponId)).isEqualTo(String.valueOf(KEYS_EXPIRE_AT.toEpochMilli()));
    }

    @Test
    @DisplayName("없는 key는 만들지 않고, 만료가 없는 key는 만료 없이 둔다")
    void doesNotCreateOrExpirePersistentKeys() {
        long couponId = newCouponId();
        redisTemplate.opsForHash().put(CouponRedisKeys.requests(couponId), "req-1", "value");

        int extended = store.extendKeysExpiry(couponId, NOW.plus(Duration.ofDays(7)));

        assertThat(extended).isZero();
        assertThat(expireTime(CouponRedisKeys.requests(couponId))).isEqualTo(-1);
        assertThat(redisTemplate.hasKey(CouponRedisKeys.issueControl(couponId))).isFalse();
        assertThat(redisTemplate.hasKey(CouponRedisKeys.stock(couponId))).isFalse();
    }

    @Test
    @DisplayName("key 타입이 틀리면 아무것도 바꾸지 않고 저장소 오류다")
    void wrongTypeFails() {
        long couponId = newCouponId();
        open(control(couponId, 1, 10));
        redisTemplate.opsForValue().set(CouponRedisKeys.users(couponId), "not-a-set");

        assertThatThrownBy(() -> store.extendKeysExpiry(couponId, KEYS_EXPIRE_AT.plus(Duration.ofDays(3))))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(expireTime(CouponRedisKeys.issueControl(couponId))).isEqualTo(KEYS_EXPIRE_AT.toEpochMilli());
        assertThat(keysExpireAtField(couponId)).isEqualTo(String.valueOf(KEYS_EXPIRE_AT.toEpochMilli()));
    }

    private long expireTime(String key) {
        return redisTemplate.execute(PEXPIRETIME, List.of(key));
    }

    private Object keysExpireAtField(long couponId) {
        return redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "keysExpireAt");
    }
}
