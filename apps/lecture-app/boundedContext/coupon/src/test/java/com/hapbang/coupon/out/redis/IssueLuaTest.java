package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisCallback;

import com.hapbang.coupon.app.port.CouponIssueStore.IssueAttempt;

/** issue.lua 반환값과 기록: 실제 Redis로 확인한다 */
class IssueLuaTest extends CouponRedisTestSupport {

    private static final long EPOCH = 3;
    private static final long MEMBER_ID = 7;

    @Test
    @DisplayName("접수하면 1이고 stock −1, users·requests 기록, Stream 1건, 만료가 붙는다")
    void accepted() {
        long couponId = newCouponId();
        String stream = newStreamKey();
        open(control(couponId, EPOCH, 10));
        IssueAttempt attempt = attempt(couponId, MEMBER_ID, "req-1", EPOCH);

        assertThat(store.tryIssue(attempt, stream)).isEqualTo(1);

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(redisTemplate.opsForSet().isMember(CouponRedisKeys.users(couponId), "7")).isTrue();
        assertThat(redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), "req-1"))
                .isEqualTo("7|" + NOW.toEpochMilli() + "|ACCEPTED");
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(stream, Range.unbounded());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "issueRequestId", "req-1",
                "couponId", String.valueOf(couponId),
                "memberId", "7",
                "epoch", "3",
                "issuedAt", String.valueOf(NOW.toEpochMilli()),
                "expiresAt", String.valueOf(attempt.expiresAt().toEpochMilli())));
        long expectedTtl = KEYS_EXPIRE_AT.toEpochMilli() - System.currentTimeMillis();
        for (String key : List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId))) {
            assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).as(key)
                    .isBetween(expectedTtl - 60_000, expectedTtl + 1_000);
        }
        assertThat(redisTemplate.getExpire(stream)).isEqualTo(-1);
        assertThat(redisTemplate.hasKey(CouponRedisKeys.soldOut(couponId))).isFalse();
    }

    @Test
    @DisplayName("운영 공통 Stream에도 같은 필드로 1건 쌓인다")
    void acceptedToCommonStream() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-common", EPOCH))).isEqualTo(1);

        List<MapRecord<String, Object, Object>> mine = redisTemplate.opsForStream()
                .range(CouponRedisKeys.ISSUE_STREAM, Range.unbounded()).stream()
                .filter(record -> String.valueOf(couponId).equals(record.getValue().get("couponId")))
                .toList();
        try {
            assertThat(mine).hasSize(1);
            assertThat(mine.getFirst().getValue()).containsEntry("issueRequestId", "req-common");
        } finally {
            mine.forEach(record -> redisTemplate.opsForStream().delete(CouponRedisKeys.ISSUE_STREAM, record.getId()));
        }
    }

    @Test
    @DisplayName("같은 회원의 같은 요청 ID는 2이고 기록을 바꾸지 않는다")
    void sameRequestAgain() {
        long couponId = newCouponId();
        String stream = newStreamKey();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, MEMBER_ID, "req-1", EPOCH), stream);

        long result = store.tryIssue(attemptAt(couponId, MEMBER_ID, "req-1", EPOCH, NOW.plusSeconds(5)), stream);

        assertThat(result).isEqualTo(2);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), "req-1"))
                .isEqualTo("7|" + NOW.toEpochMilli() + "|ACCEPTED");
        assertThat(redisTemplate.opsForStream().size(stream)).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회원이 같은 요청 ID를 쓰면 -4다")
    void requestIdOfAnotherMember() {
        long couponId = newCouponId();
        String stream = newStreamKey();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, MEMBER_ID, "req-1", EPOCH), stream);

        assertThat(store.tryIssue(attempt(couponId, 8, "req-1", EPOCH), stream)).isEqualTo(-4);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
    }

    @Test
    @DisplayName("이미 접수한 회원의 새 요청 ID는 -1이다")
    void alreadyIssued() {
        long couponId = newCouponId();
        String stream = newStreamKey();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, MEMBER_ID, "req-1", EPOCH), stream);

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-2", EPOCH), stream)).isEqualTo(-1);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
    }

    @Test
    @DisplayName("마지막 1장이면 sold_out을 남기고, 그 뒤 접수는 0이다")
    void soldOut() {
        long couponId = newCouponId();
        String stream = newStreamKey();
        open(control(couponId, EPOCH, 1));

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1", EPOCH), stream)).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.soldOut(couponId))).isEqualTo("1");
        assertThat(redisTemplate.getExpire(CouponRedisKeys.soldOut(couponId))).isPositive();

        assertThat(store.tryIssue(attempt(couponId, 8, "req-2", EPOCH), stream)).isZero();
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("0");
        assertThat(redisTemplate.opsForStream().size(stream)).isEqualTo(1);
    }

    @Test
    @DisplayName("issue-control이 없으면 -2다")
    void notReady() {
        long couponId = newCouponId();

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1", EPOCH), newStreamKey())).isEqualTo(-2);
    }

    @Test
    @DisplayName("중단(SUSPENDED)된 쿠폰은 -2다")
    void suspended() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.block(couponId, "SUSPENDED", 1);

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1", EPOCH), newStreamKey())).isEqualTo(-2);
    }

    @Test
    @DisplayName("epoch가 다르면 -2다")
    void epochMismatch() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1", EPOCH - 1), newStreamKey())).isEqualTo(-2);
    }

    @Test
    @DisplayName("발급 시작 전이면 -3이다")
    void beforeStart() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));

        IssueAttempt early = attemptAt(couponId, MEMBER_ID, "req-1", EPOCH, START.minusMillis(1));
        assertThat(store.tryIssue(early, newStreamKey())).isEqualTo(-3);
    }

    @Test
    @DisplayName("발급 종료 시각부터는 -3이다")
    void atEnd() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));

        IssueAttempt late = attemptAt(couponId, MEMBER_ID, "req-1", EPOCH, END);
        assertThat(store.tryIssue(late, newStreamKey())).isEqualTo(-3);
    }

    @Test
    @DisplayName("key 타입이 틀리면 쓰기 전에 -9다")
    void wrongKeyType() {
        long couponId = newCouponId();
        String stream = newStreamKey();
        open(control(couponId, EPOCH, 10));
        redisTemplate.opsForValue().set(CouponRedisKeys.users(couponId), "not-a-set");

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1", EPOCH), stream)).isEqualTo(-9);

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("10");
        assertThat(redisTemplate.hasKey(CouponRedisKeys.requests(couponId))).isFalse();
        assertThat(redisTemplate.hasKey(stream)).isFalse();
    }

    @Test
    @DisplayName("XADD가 실패하면 stock·users·requests를 되돌리고 -9다")
    void rollbackWhenXaddFails() {
        long couponId = newCouponId();
        String stream = newStreamKey();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, 8, "req-0", EPOCH), stream);
        // 마지막 ID를 최댓값으로 채운 Stream은 타입 검사를 통과하지만 XADD *가 "ID를 다 썼다" 오류를 낸다
        exhaustStreamIds(stream);

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1", EPOCH), stream)).isEqualTo(-9);

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(redisTemplate.opsForSet().members(CouponRedisKeys.users(couponId))).containsExactly("8");
        assertThat(redisTemplate.opsForHash().keys(CouponRedisKeys.requests(couponId))).containsExactly("req-0");
        assertThat(redisTemplate.opsForStream().size(stream)).isEqualTo(2);
    }

    private void exhaustStreamIds(String stream) {
        redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute("XADD",
                bytes(stream), bytes("18446744073709551615-18446744073709551615"), bytes("filler"), bytes("1")));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
