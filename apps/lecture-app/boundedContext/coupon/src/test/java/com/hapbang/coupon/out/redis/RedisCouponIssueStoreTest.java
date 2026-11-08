package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.coupon.app.port.CouponIssueStore.AllowResult;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueControlState;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueReceipt;
import com.hapbang.coupon.app.port.CouponIssueStore.PublishMark;
import com.hapbang.coupon.app.port.CouponIssueStore.RequestEntry;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;

/** 발급 준비·접수 열기·막기(fence 순서)·접수 기록 조회: 실제 Redis로 확인한다 */
class RedisCouponIssueStoreTest extends CouponRedisTestSupport {

    private static final long EPOCH = 1;

    @Test
    @DisplayName("prepare는 만료 계산 입력까지 쓰지만 접수를 열지 않고(-2), allowIssue 뒤에 접수된다")
    void prepareDoesNotOpen() {
        long couponId = newCouponId();

        store.prepare(control(couponId, EPOCH, 10));

        assertThat(store.tryIssue(attempt(couponId, 7, "req-1")).code()).isEqualTo(-2);
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
        assertThat(store.tryIssue(attempt(couponId, 7, "req-1")).code()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 epoch로 다시 prepare해도 줄어든 stock·열린 상태·fence를 덮지 않는다")
    void prepareAgainKeepsStockStatusAndFence() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, 7, "req-1"));

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
        store.tryIssue(attempt(couponId, 7, "req-1"));

        assertThat(store.findRequest(couponId, "req-1")).contains(new IssueReceipt(7, NOW));
        assertThat(store.findRequest(couponId, "req-unknown")).isEmpty();
    }

    @Test
    @DisplayName("findRequest는 표식이 붙은 기록도 읽고, 형식이 틀리면 저장소 오류다")
    void findRequestWithMarksAndMalformed() {
        long couponId = newCouponId();
        String requests = CouponRedisKeys.requests(couponId);
        redisTemplate.opsForHash().put(requests, "req-1", "7|" + NOW.toEpochMilli() + "|ACCEPTED|1|PUBLISHED|COMPENSATED");
        redisTemplate.opsForHash().put(requests, "req-3", "7|" + NOW.toEpochMilli() + "|ACCEPTED|1|e3|PUBLISHED");
        redisTemplate.opsForHash().put(requests, "req-2", "7|" + NOW.toEpochMilli() + "|ISSUED|1");

        assertThat(store.findRequest(couponId, "req-1")).contains(new IssueReceipt(7, NOW));
        assertThat(store.findRequest(couponId, "req-3")).contains(new IssueReceipt(7, NOW));
        assertThatThrownBy(() -> store.findRequest(couponId, "req-2"))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
    }

    @Test
    @DisplayName("mark-published: 표식을 한 번만 붙이고 만료를 지키며, 기록이 없거나 보상을 마친 요청은 바꾸지 않는다")
    void markPublished() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, 7, "req-1"));
        String requests = CouponRedisKeys.requests(couponId);
        Object accepted = redisTemplate.opsForHash().get(requests, "req-1");

        assertThat(store.markPublished(couponId, "req-1")).isEqualTo(PublishMark.MARKED);
        assertThat(store.markPublished(couponId, "req-1")).isEqualTo(PublishMark.ALREADY_MARKED);
        assertThat(redisTemplate.opsForHash().get(requests, "req-1")).isEqualTo(accepted + "|PUBLISHED");
        assertThat(redisTemplate.getExpire(requests)).isPositive();

        redisTemplate.opsForHash().put(requests, "req-2", accepted + "|COMPENSATED");
        assertThat(store.markPublished(couponId, "req-2")).isEqualTo(PublishMark.SKIPPED);
        assertThat(redisTemplate.opsForHash().get(requests, "req-2")).isEqualTo(accepted + "|COMPENSATED");

        assertThat(store.markPublished(couponId, "req-unknown")).isEqualTo(PublishMark.SKIPPED);
        assertThat(redisTemplate.opsForHash().hasKey(requests, "req-unknown")).isFalse();
        long otherCoupon = newCouponId();
        assertThat(store.markPublished(otherCoupon, "req-1")).isEqualTo(PublishMark.SKIPPED);
        assertThat(redisTemplate.hasKey(CouponRedisKeys.requests(otherCoupon))).isFalse();
    }

    @Test
    @DisplayName("mark-published: 접수 epoch를 지키고, 접수 epoch가 없는 옛 형식 기록에는 epoch 없이 표식만 붙인다")
    void markPublishedKeepsEpochField() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 10));
        store.tryIssue(attempt(couponId, 7, "req-new"));
        String requests = CouponRedisKeys.requests(couponId);
        String accepted = (String) redisTemplate.opsForHash().get(requests, "req-new");
        String old = "8|" + NOW.toEpochMilli() + "|ACCEPTED|1";
        redisTemplate.opsForHash().put(requests, "req-old", old);
        redisTemplate.opsForHash().put(requests, "req-old-compensated", old + "|COMPENSATED");

        assertThat(store.markPublished(couponId, "req-new")).isEqualTo(PublishMark.MARKED);
        assertThat(store.markPublished(couponId, "req-old")).isEqualTo(PublishMark.MARKED);
        assertThat(store.markPublished(couponId, "req-old")).isEqualTo(PublishMark.ALREADY_MARKED);
        assertThat(store.markPublished(couponId, "req-old-compensated")).isEqualTo(PublishMark.SKIPPED);

        assertThat(accepted).endsWith("|e" + EPOCH);
        assertThat(redisTemplate.opsForHash().get(requests, "req-new")).isEqualTo(accepted + "|PUBLISHED");
        assertThat(redisTemplate.opsForHash().get(requests, "req-old")).isEqualTo(old + "|PUBLISHED");
        assertThat(redisTemplate.opsForHash().get(requests, "req-old-compensated")).isEqualTo(old + "|COMPENSATED");
    }

    @Test
    @DisplayName("mark-published: 기록 형식이 틀리면 쓰지 않고 저장소 오류다")
    void markPublishedMalformed() {
        long couponId = newCouponId();
        redisTemplate.opsForHash().put(CouponRedisKeys.requests(couponId), "req-1", "broken");

        assertThatThrownBy(() -> store.markPublished(couponId, "req-1"))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThat(redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), "req-1")).isEqualTo("broken");
    }

    @Test
    @DisplayName("isSoldOut은 마지막 1장 접수 뒤 true다")
    void isSoldOut() {
        long couponId = newCouponId();
        open(control(couponId, EPOCH, 1));
        assertThat(store.isSoldOut(couponId)).isFalse();

        store.tryIssue(attempt(couponId, 7, "req-1"));

        assertThat(store.isSoldOut(couponId)).isTrue();
    }

    @Test
    @DisplayName("scanRequests는 접수 기록 전부를 묶음 크기 이하로 나눠 넘기고, hasRequests는 기록이 있을 때만 true다")
    void scanRequests() {
        long couponId = newCouponId();
        assertThat(store.hasRequests(couponId)).isFalse();
        String requests = CouponRedisKeys.requests(couponId);
        for (int i = 0; i < 25; i++) {
            redisTemplate.opsForHash().put(requests, "req-" + i, "value-" + i);
        }

        List<List<RequestEntry>> pages = new ArrayList<>();
        store.scanRequests(couponId, 10, pages::add);

        assertThat(store.hasRequests(couponId)).isTrue();
        assertThat(pages).allSatisfy(page -> assertThat(page).hasSizeBetween(1, 10));
        List<RequestEntry> all = new ArrayList<>();
        pages.forEach(all::addAll);
        assertThat(all).extracting(RequestEntry::issueRequestId).doesNotHaveDuplicates().hasSize(25);
        assertThat(all).contains(new RequestEntry("req-3", "value-3"));
    }

    @Test
    @DisplayName("findIssueControl은 준비·상태·epoch·보류를 읽고, issue-control이 없으면 빈 값이다")
    void findIssueControl() {
        long couponId = newCouponId();
        assertThat(store.findIssueControl(couponId)).isEmpty();

        open(control(couponId, 4, 10));
        assertThat(store.findIssueControl(couponId)).contains(new IssueControlState(true, "OPEN", 4L, null));

        redisTemplate.opsForHash().put(CouponRedisKeys.issueControl(couponId), "hold", "RECOVERY");
        redisTemplate.opsForHash().put(CouponRedisKeys.issueControl(couponId), "ready", "0");
        assertThat(store.findIssueControl(couponId)).contains(new IssueControlState(false, "OPEN", 4L, "RECOVERY"));
    }
}
