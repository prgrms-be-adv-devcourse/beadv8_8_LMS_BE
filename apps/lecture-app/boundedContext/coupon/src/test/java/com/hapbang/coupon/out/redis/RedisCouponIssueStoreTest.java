package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.port.CouponIssueStore.AllowResult;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueReceipt;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;

/** 발급 준비·접수 열기·막기(fence 순서)·접수 기록 조회: 실제 Redis로 확인한다 */
class RedisCouponIssueStoreTest extends CouponRedisTestSupport {

    private static final long EPOCH = 1;

    @Test
    @DisplayName("prepare는 만료 계산 입력까지 쓰지만 접수를 열지 않고(-2), allowIssue 뒤에 접수된다")
    void prepareDoesNotOpen() {
        long couponId = newCouponId();

        store.prepare(control(couponId, EPOCH, 10));

        assertThat(store.tryIssue(attempt(couponId, 7, "req-1"), newStreamKey()).code()).isEqualTo(-2);
        assertThat(redisTemplate.<String, String>opsForHash().entries(CouponRedisKeys.issueControl(couponId)))
                .containsEntry("ready", "1")
                .containsEntry("status", "READY")
                .containsEntry("epoch", "1")
                .containsEntry("fence", "0")
                .containsEntry("startAt", String.valueOf(START.toEpochMilli()))
                .containsEntry("endAt", String.valueOf(END.toEpochMilli()))
                .containsEntry("keysExpireAt", String.valueOf(KEYS_EXPIRE_AT.toEpochMilli()))
                .containsEntry("expiryType", "RELATIVE")
                .containsEntry("expiryDays", String.valueOf(EXPIRY_DAYS))
                .doesNotContainKey("fixedExpiresAt");

        assertThat(store.allowIssue(couponId, 1)).isEqualTo(AllowResult.APPLIED);
        assertThat(store.tryIssue(attempt(couponId, 7, "req-1"), newStreamKey()).code()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 epoch로 다시 prepare해도 줄어든 stock·열린 상태·fence를 덮지 않는다")
    void prepareAgainKeepsStockStatusAndFence() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, 7, "req-1"), newStreamKey());

        store.prepare(control(couponId, EPOCH, 10));

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(controlStatus(couponId)).isEqualTo("OPEN");
        assertThat(redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "fence")).isEqualTo("1");
    }

    @Test
    @DisplayName("새 epoch로 고정 만료를 준비하면 고정 만료 시각(밀리초 아래 버림)을 쓰고 이전 만료 일수를 지운다")
    void prepareFixedExpiryReplacesRelative() {
        long couponId = newCouponId();
        store.prepare(control(couponId, EPOCH, 10));
        Instant fixedExpiresAt = END.plus(Duration.ofDays(3)).plusNanos(456_789_000);

        store.prepare(fixedControl(couponId, EPOCH + 1, 10, fixedExpiresAt));

        assertThat(redisTemplate.<String, String>opsForHash().entries(CouponRedisKeys.issueControl(couponId)))
                .containsEntry("expiryType", "FIXED")
                .containsEntry("fixedExpiresAt", String.valueOf(fixedExpiresAt.toEpochMilli()))
                .doesNotContainKey("expiryDays");
    }

    @Test
    @DisplayName("allowIssue는 저장된 fence보다 클 때만 연다: 더 새 열기 뒤의 옛 열기는 SUPERSEDED다")
    void staleAllowIssueIsIgnored() {
        long couponId = newCouponId();
        store.prepare(control(couponId, EPOCH, 10));
        store.allowIssue(couponId, 3);

        assertThat(store.allowIssue(couponId, 2)).isEqualTo(AllowResult.SUPERSEDED);
        assertThat(store.allowIssue(couponId, 3)).isEqualTo(AllowResult.SUPERSEDED);
        assertThat(redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "fence")).isEqualTo("3");
    }

    @Test
    @DisplayName("같은 fence로 먼저 막으면 그 fence로 늦게 온 allowIssue는 열지 못하고, 더 큰 fence면 연다")
    void blockWithSameFenceBeatsLateAllowIssue() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));

        store.block(couponId, "SUSPENDED", 2);

        assertThat(store.allowIssue(couponId, 2)).isEqualTo(AllowResult.SUPERSEDED);
        assertThat(controlStatus(couponId)).isEqualTo("SUSPENDED");
        assertThat(store.allowIssue(couponId, 3)).isEqualTo(AllowResult.APPLIED);
        assertThat(controlStatus(couponId)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("block은 저장된 fence보다 작으면 무시하고 성공한다: 더 새 열기 뒤의 옛 막기")
    void staleBlockIsIgnored() {
        long couponId = newCouponId();
        store.prepare(control(couponId, EPOCH, 10));
        store.allowIssue(couponId, 3);

        store.block(couponId, "SUSPENDED", 2);

        assertThat(controlStatus(couponId)).isEqualTo("OPEN");
        assertThat(redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "fence")).isEqualTo("3");
    }

    @Test
    @DisplayName("종료(CLOSED)한 발급은 SUSPENDED로 되돌리지 않고, allowIssue·같은 epoch prepare로 다시 열지 않는다")
    void closedStaysClosed() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.block(couponId, "CLOSED", 2);

        store.block(couponId, "SUSPENDED", 3);

        assertThat(controlStatus(couponId)).isEqualTo("CLOSED");
        assertThatThrownBy(() -> store.allowIssue(couponId, 4)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.prepare(control(couponId, EPOCH, 10))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("준비 전(issue-control 없음) block은 이미 막힌 것으로 보고 key를 만들지 않으며, allowIssue는 예외다")
    void beforeReady() {
        long couponId = newCouponId();

        store.block(couponId, "SUSPENDED", 0);

        assertThat(redisTemplate.hasKey(CouponRedisKeys.issueControl(couponId))).isFalse();
        assertThatThrownBy(() -> store.allowIssue(couponId, 1))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
    }

    @Test
    @DisplayName("findRequest는 접수한 회원과 최초 접수 시각을 돌려주고, 없으면 빈 값이다")
    void findRequest() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, 7, "req-1"), newStreamKey());

        assertThat(store.findRequest(couponId, "req-1")).contains(new IssueReceipt(7, NOW));
        assertThat(store.findRequest(couponId, "req-unknown")).isEmpty();
    }
}
