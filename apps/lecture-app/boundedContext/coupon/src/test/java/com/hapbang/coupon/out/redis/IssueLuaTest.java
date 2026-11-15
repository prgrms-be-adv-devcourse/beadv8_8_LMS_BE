package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.port.CouponIssueStore.IssueAttempt;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueOutcome;

/** issue.lua 반환값과 기록(쓰기 실패 되돌리기 포함): 실제 Redis로 확인한다 */
class IssueLuaTest extends CouponRedisTestSupport {

    private static final long EPOCH = 3;
    private static final long MEMBER_ID = 7;

    @Test
    @DisplayName("접수하면 1과 접수 시각 + N일 만료 시각·issue-control epoch이고 stock −1, users·requests(접수 epoch 포함) 기록, key 만료가 붙는다")
    void accepted() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        Instant expiresAt = NOW.plus(Duration.ofDays(EXPIRY_DAYS));

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")))
                .isEqualTo(new IssueOutcome(1, expiresAt, EPOCH));

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(redisTemplate.opsForSet().isMember(CouponRedisKeys.users(couponId), "7")).isTrue();
        assertThat(redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), "req-1"))
                .isEqualTo("7|" + NOW.toEpochMilli() + "|ACCEPTED|" + expiresAt.toEpochMilli() + "|e" + EPOCH);
        long expectedTtl = KEYS_EXPIRE_AT.toEpochMilli() - System.currentTimeMillis();
        for (String key : List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId))) {
            assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).as(key)
                    .isBetween(expectedTtl - 60_000, expectedTtl + 1_000);
        }
        assertThat(redisTemplate.hasKey(CouponRedisKeys.soldOut(couponId))).isFalse();
    }

    @Test
    @DisplayName("같은 회원의 같은 요청 ID는 2와 최초 접수의 만료 시각이고 기록을 바꾸지 않는다")
    void sameRequestAgain() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        IssueOutcome first = store.tryIssue(attempt(couponId, MEMBER_ID, "req-1"));
        Object record = redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), "req-1");

        IssueOutcome again = store.tryIssue(attemptAt(couponId, MEMBER_ID, "req-1", NOW.plusSeconds(5)));

        assertThat(again).isEqualTo(new IssueOutcome(2, first.expiresAt(), 0));
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), "req-1")).isEqualTo(record);
    }

    @Test
    @DisplayName("표식(PUBLISHED·COMPENSATED)이 붙은 기록도 같은 회원의 재전송은 2와 최초 만료 시각이다")
    void sameRequestAgainWithMarks() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        IssueOutcome first = store.tryIssue(attempt(couponId, MEMBER_ID, "req-1"));
        String requests = CouponRedisKeys.requests(couponId);
        Object accepted = redisTemplate.opsForHash().get(requests, "req-1");
        redisTemplate.opsForHash().put(requests, "req-1", accepted + "|COMPENSATED|PUBLISHED");

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")))
                .isEqualTo(new IssueOutcome(2, first.expiresAt(), 0));
        assertThat(store.tryIssue(attempt(couponId, 8, "req-1")).code()).isEqualTo(-4);
    }

    @Test
    @DisplayName("접수 epoch가 없는 옛 형식 기록(표식 포함)도 같은 회원의 재전송은 2와 최초 만료 시각이고 기록을 바꾸지 않는다")
    void sameRequestAgainWithOldFormat() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        long expiresAt = NOW.plus(Duration.ofDays(EXPIRY_DAYS)).toEpochMilli();
        String requests = CouponRedisKeys.requests(couponId);
        String old = MEMBER_ID + "|" + NOW.toEpochMilli() + "|ACCEPTED|" + expiresAt;
        redisTemplate.opsForHash().put(requests, "req-old", old);
        redisTemplate.opsForHash().put(requests, "req-old-marked", old + "|PUBLISHED");

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-old")))
                .isEqualTo(new IssueOutcome(2, Instant.ofEpochMilli(expiresAt), 0));
        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-old-marked")).code()).isEqualTo(2);
        assertThat(store.tryIssue(attempt(couponId, 8, "req-old")).code()).isEqualTo(-4);
        assertThat(redisTemplate.opsForHash().get(requests, "req-old")).isEqualTo(old);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("10");
    }

    @Test
    @DisplayName("기록 뒤에 표식 구분자 없이 글자가 붙어 있으면 -9다")
    void malformedMarks() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        redisTemplate.opsForHash().put(CouponRedisKeys.requests(couponId), "req-1",
                MEMBER_ID + "|" + NOW.toEpochMilli() + "|ACCEPTED|" + NOW.toEpochMilli() + "PUBLISHED");

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")).code()).isEqualTo(-9);
    }

    @Test
    @DisplayName("issue-control에 hold가 있으면 신규 접수는 -2이고 쓰지 않지만, 이미 접수한 요청의 재전송은 2다")
    void hold() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        IssueOutcome first = store.tryIssue(attempt(couponId, MEMBER_ID, "req-1"));
        redisTemplate.opsForHash().put(CouponRedisKeys.issueControl(couponId), "hold", "BACKLOG");

        assertThat(store.tryIssue(attempt(couponId, 8, "req-2"))).isEqualTo(new IssueOutcome(-2, null, 0));
        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")))
                .isEqualTo(new IssueOutcome(2, first.expiresAt(), 0));
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(redisTemplate.opsForHash().keys(CouponRedisKeys.requests(couponId))).containsExactly("req-1");

        // hold를 빈 값으로 두면 보류가 아니다
        redisTemplate.opsForHash().put(CouponRedisKeys.issueControl(couponId), "hold", "");
        assertThat(store.tryIssue(attempt(couponId, 8, "req-2")).code()).isEqualTo(1);
    }

    @Test
    @DisplayName("재응답(2)은 Lua 반환에 기록의 접수 epoch를 싣고, epoch 필드가 없는 옛 형식 기록이면 0이다")
    void sameRequestReturnsAcceptedEpoch() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        IssueOutcome first = store.tryIssue(attempt(couponId, MEMBER_ID, "req-1"));
        long expiresAt = first.expiresAt().toEpochMilli();
        redisTemplate.opsForHash().put(CouponRedisKeys.requests(couponId), "req-old",
                MEMBER_ID + "|" + NOW.toEpochMilli() + "|ACCEPTED|" + expiresAt + "|PUBLISHED");

        assertThat(runIssue(couponId, MEMBER_ID, "req-1", NOW)).containsExactly(2L, expiresAt, EPOCH);
        assertThat(runIssue(couponId, MEMBER_ID, "req-old", NOW)).containsExactly(2L, expiresAt, 0L);
    }

    @Test
    @DisplayName("중단(SUSPENDED)·hold 뒤에도 같은 요청은 2와 최초 만료 시각, 다른 회원은 -4이고, 신규 요청만 -2다")
    void sameRequestAfterSuspend() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        IssueOutcome first = store.tryIssue(attempt(couponId, MEMBER_ID, "req-1"));
        store.block(couponId, "SUSPENDED", 1);
        redisTemplate.opsForHash().put(CouponRedisKeys.issueControl(couponId), "hold", "RECOVERY");

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")))
                .isEqualTo(new IssueOutcome(2, first.expiresAt(), 0));
        assertThat(store.tryIssue(attempt(couponId, 8, "req-1")).code()).isEqualTo(-4);
        assertThat(store.tryIssue(attempt(couponId, 8, "req-2")).code()).isEqualTo(-2);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
    }

    @Test
    @DisplayName("issue-control이 없어도 requests에 기록이 남은 같은 요청은 2이고, 신규 요청은 -2다")
    void sameRequestWithoutControl() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        IssueOutcome first = store.tryIssue(attempt(couponId, MEMBER_ID, "req-1"));
        redisTemplate.delete(CouponRedisKeys.issueControl(couponId));

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")))
                .isEqualTo(new IssueOutcome(2, first.expiresAt(), 0));
        assertThat(store.tryIssue(attempt(couponId, 8, "req-2")).code()).isEqualTo(-2);
    }

    @Test
    @DisplayName("발급 기간이 끝난 뒤에도 같은 요청은 2와 최초 만료 시각, 다른 회원은 -4이고, 신규 요청만 -3이다")
    void sameRequestAfterEnd() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        IssueOutcome first = store.tryIssue(attempt(couponId, MEMBER_ID, "req-1"));

        assertThat(store.tryIssue(attemptAt(couponId, MEMBER_ID, "req-1", END)))
                .isEqualTo(new IssueOutcome(2, first.expiresAt(), 0));
        assertThat(store.tryIssue(attemptAt(couponId, 8, "req-1", END)).code()).isEqualTo(-4);
        assertThat(store.tryIssue(attemptAt(couponId, 8, "req-2", END)).code()).isEqualTo(-3);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
    }

    @Test
    @DisplayName("다른 회원이 같은 요청 ID를 쓰면 -4다")
    void requestIdOfAnotherMember() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, MEMBER_ID, "req-1"));

        assertThat(store.tryIssue(attempt(couponId, 8, "req-1")).code()).isEqualTo(-4);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
    }

    @Test
    @DisplayName("이미 접수한 회원의 새 요청 ID는 -1이다")
    void alreadyIssued() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, MEMBER_ID, "req-1"));

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-2")).code()).isEqualTo(-1);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
    }

    @Test
    @DisplayName("마지막 1장이면 sold_out을 남기고, 그 뒤 접수는 0이다")
    void soldOut() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 1));

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")).code()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.soldOut(couponId))).isEqualTo("1");
        assertThat(redisTemplate.getExpire(CouponRedisKeys.soldOut(couponId))).isPositive();

        assertThat(store.tryIssue(attempt(couponId, 8, "req-2")).code()).isZero();
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("0");
        assertThat(redisTemplate.opsForHash().keys(CouponRedisKeys.requests(couponId))).containsExactly("req-1");
    }

    @Test
    @DisplayName("issue-control이 없으면 -2다")
    void notReady() {
        long couponId = newCouponId();

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")).code()).isEqualTo(-2);
    }

    @Test
    @DisplayName("중단(SUSPENDED)된 쿠폰은 -2다")
    void suspended() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.block(couponId, "SUSPENDED", 1);

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")).code()).isEqualTo(-2);
    }

    @Test
    @DisplayName("발급 시작 전이면 -3이다")
    void beforeStart() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));

        IssueAttempt early = attemptAt(couponId, MEMBER_ID, "req-1", START.minusMillis(1));
        assertThat(store.tryIssue(early).code()).isEqualTo(-3);
    }

    @Test
    @DisplayName("발급 종료 시각부터는 -3이다")
    void atEnd() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));

        IssueAttempt late = attemptAt(couponId, MEMBER_ID, "req-1", END);
        assertThat(store.tryIssue(late).code()).isEqualTo(-3);
    }

    @Test
    @DisplayName("고정 만료 쿠폰은 접수 시각과 관계없이 issue-control의 고정 만료 시각으로 접수한다")
    void fixedExpiry() {
        long couponId = newCouponId();
        Instant fixedExpiresAt = END.plus(Duration.ofDays(10));
        open(fixedControl(couponId, EPOCH, 10, fixedExpiresAt));

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")))
                .isEqualTo(new IssueOutcome(1, fixedExpiresAt, EPOCH));

        assertThat((String) redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), "req-1"))
                .endsWith("|ACCEPTED|" + fixedExpiresAt.toEpochMilli() + "|e" + EPOCH);
    }

    @Test
    @DisplayName("issue-control에 만료 계산 입력이 없으면 쓰기 전에 -9다")
    void missingExpiryInput() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        redisTemplate.opsForHash().delete(CouponRedisKeys.issueControl(couponId), "expiryDays");

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")))
                .isEqualTo(new IssueOutcome(-9, null, 0));

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("10");
        assertThat(redisTemplate.hasKey(CouponRedisKeys.users(couponId))).isFalse();
        assertThat(redisTemplate.hasKey(CouponRedisKeys.requests(couponId))).isFalse();
    }

    @Test
    @DisplayName("key 타입이 틀리면 쓰기 전에 -9다")
    void wrongKeyType() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        redisTemplate.opsForValue().set(CouponRedisKeys.users(couponId), "not-a-set");

        assertThat(store.tryIssue(attempt(couponId, MEMBER_ID, "req-1")).code()).isEqualTo(-9);

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("10");
        assertThat(redisTemplate.hasKey(CouponRedisKeys.requests(couponId))).isFalse();
    }

    @Test
    @DisplayName("requests 기록(HSET)이 실패하면 같은 스크립트에서 stock·users를 되돌리고 -9다")
    void rollbackWhenRequestWriteFails() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, 8, "req-0"));
        // users와 requests 자리에 아직 없는 같은 key를 넘긴다. 쓰기 전 타입 검사는 둘 다 none이라 통과하고, SADD가 그 key를 Set으로
        // 만든 뒤의 HSET은 WRONGTYPE으로 실패한다. 운영 스크립트를 바꾸지 않고 뒤의 쓰기 실패를 만든다
        String sharedKey = track(CouponRedisKeys.users(couponId) + ":rollback-test");
        List<String> keys = List.of(CouponRedisKeys.issueControl(couponId), CouponRedisKeys.stock(couponId), sharedKey,
                sharedKey, CouponRedisKeys.soldOut(couponId));

        List<Long> result = CouponRedisLuaSupport.runForLongs(redisTemplate, RedisCouponIssueStore.ISSUE, keys,
                couponId, MEMBER_ID, "req-1", NOW.toEpochMilli());

        assertThat(result).containsExactly(-9L, 0L, 0L);
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(redisTemplate.hasKey(sharedKey)).isFalse();
        assertThat(redisTemplate.opsForSet().members(CouponRedisKeys.users(couponId))).containsExactly("8");
        assertThat(redisTemplate.opsForHash().keys(CouponRedisKeys.requests(couponId))).containsExactly("req-0");
        assertThat(redisTemplate.hasKey(CouponRedisKeys.soldOut(couponId))).isFalse();
    }

    /** 스토어를 거치지 않고 issue.lua 반환을 그대로 본다(스토어는 재응답의 epoch를 쓰지 않는다) */
    private List<Long> runIssue(long couponId, long memberId, String requestId, Instant issuedAt) {
        return CouponRedisLuaSupport.runForLongs(redisTemplate, RedisCouponIssueStore.ISSUE,
                RedisCouponIssueStore.issueKeys(couponId), couponId, memberId, requestId, issuedAt.toEpochMilli());
    }
}
