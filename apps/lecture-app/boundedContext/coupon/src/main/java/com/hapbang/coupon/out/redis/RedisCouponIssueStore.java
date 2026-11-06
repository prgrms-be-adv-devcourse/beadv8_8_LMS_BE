package com.hapbang.coupon.out.redis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.port.CouponIssueStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;

/**
 * Redis 발급 접수 저장소. 판정·기록은 {@code resources/lua}의 스크립트가 원자적으로 하고, key는 {@link CouponRedisKeys}가 만든다.
 * 시각은 모두 epoch 밀리초로 저장한다(밀리초 아래는 버린다).
 */
@Component
public class RedisCouponIssueStore implements CouponIssueStore {

    static final String STATUS_SUSPENDED = "SUSPENDED";
    static final String STATUS_CLOSED = "CLOSED";

    private static final RedisScript<Long> ISSUE = CouponRedisLuaSupport.longLuaScript("lua/issue.lua");
    private static final RedisScript<Long> PREPARE = CouponRedisLuaSupport.longLuaScript("lua/prepare.lua");
    private static final RedisScript<Long> ALLOW_ISSUE = CouponRedisLuaSupport.longLuaScript("lua/allow-issue.lua");
    private static final RedisScript<Long> BLOCK = CouponRedisLuaSupport.longLuaScript("lua/block.lua");

    private final StringRedisTemplate redisTemplate;

    public RedisCouponIssueStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void prepare(IssueControl control) {
        if (control.stock() < 0 || control.fence() < 0 || !control.issueStartAt().isBefore(control.issueEndAt())
                || control.keysExpireAt().isBefore(control.issueEndAt())) {
            throw new IllegalArgumentException("발급 준비 값이 잘못되었다 couponId=" + control.couponId());
        }
        long result = run("발급 준비", () -> CouponRedisLuaSupport.runForLong(redisTemplate, PREPARE,
                List.of(CouponRedisKeys.issueControl(control.couponId()), CouponRedisKeys.stock(control.couponId())),
                control.epoch(), control.stock(), control.issueStartAt().toEpochMilli(),
                control.issueEndAt().toEpochMilli(), control.keysExpireAt().toEpochMilli(), control.fence()));
        if (result == -1) {
            throw new IllegalStateException("이미 종료된 발급은 다시 열지 않는다 couponId=" + control.couponId());
        }
        if (result != 1) {
            throw new CouponIssueStoreUnavailableException(
                    "발급 준비 데이터를 쓰지 못했다 couponId=" + control.couponId() + ", result=" + result, null);
        }
    }

    @Override
    public AllowResult allowIssue(long couponId, long fence) {
        requireFence(fence);
        long result = run("발급 접수 열기", () -> CouponRedisLuaSupport.runForLong(redisTemplate, ALLOW_ISSUE,
                List.of(CouponRedisKeys.issueControl(couponId)), fence));
        return switch ((int) result) {
            case 1 -> AllowResult.APPLIED;
            case 2 -> AllowResult.SUPERSEDED;
            case -1 -> throw new IllegalStateException("종료된 발급은 다시 열지 않는다 couponId=" + couponId);
            default -> throw new CouponIssueStoreUnavailableException(
                    "발급 준비 기록이 없거나 잘못되어 접수를 열지 못했다 couponId=" + couponId + ", result=" + result, null);
        };
    }

    /**
     * issue-control이 없으면(준비 전이거나 만료됨) 성공으로 본다. issue.lua가 issue-control이 없으면 -2로 거절하므로 이미 막힌 상태이고,
     * 만료 시각을 모르는 채로 issue-control을 만들면 만료 없는 key가 남는다. 준비({@link #prepare})는 접수를 열지 않으므로 뒤에 준비가
     * 와도 막힌 채다.
     */
    @Override
    public void block(long couponId, String status, long fence) {
        if (!STATUS_SUSPENDED.equals(status) && !STATUS_CLOSED.equals(status)) {
            throw new IllegalArgumentException("막는 상태는 SUSPENDED 또는 CLOSED다 status=" + status);
        }
        requireFence(fence);
        String controlKey = CouponRedisKeys.issueControl(couponId);
        long result = run("발급 막기",
                () -> CouponRedisLuaSupport.runForLong(redisTemplate, BLOCK, List.of(controlKey), status, fence));
        if (result == 0) {
            return;
        }
        if (result != 1 && result != 2) {
            throw new CouponIssueStoreUnavailableException(
                    "발급 차단을 쓰지 못했다 couponId=" + couponId + ", result=" + result, null);
        }
        // 다시 읽어 차단을 확인한다. 이미 CLOSED였다면 SUSPENDED 요청도 막힌 것으로 보고, 더 새 fence의 쓰기가 있으면 그 쓰기가 맡는다
        List<Object> current = run("발급 차단 확인",
                () -> redisTemplate.opsForHash().multiGet(controlKey, List.<Object>of("status", "fence")));
        Object currentStatus = current.get(0);
        if (status.equals(currentStatus) || STATUS_CLOSED.equals(currentStatus) || isNewer(current.get(1), fence)) {
            return;
        }
        throw new CouponIssueStoreUnavailableException(
                "발급 차단을 확인하지 못했다 couponId=" + couponId + ", status=" + currentStatus, null);
    }

    private static void requireFence(long fence) {
        if (fence < 0) {
            throw new IllegalArgumentException("fence는 음수일 수 없다 fence=" + fence);
        }
    }

    private static boolean isNewer(Object storedFence, long fence) {
        if (storedFence == null) {
            return false;
        }
        try {
            return Long.parseLong(storedFence.toString()) > fence;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public long tryIssue(IssueAttempt attempt) {
        return tryIssue(attempt, CouponRedisKeys.ISSUE_STREAM);
    }

    /** Stream key만 바꿔 같은 스크립트를 실행한다. 테스트가 XADD 실패(되돌리기)를 만들 때 쓴다 */
    long tryIssue(IssueAttempt attempt, String streamKey) {
        long couponId = attempt.couponId();
        List<String> keys = List.of(
                CouponRedisKeys.issueControl(couponId),
                CouponRedisKeys.stock(couponId),
                CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId),
                CouponRedisKeys.soldOut(couponId),
                streamKey);
        return run("발급 접수", () -> CouponRedisLuaSupport.runForLong(redisTemplate, ISSUE, keys,
                couponId, attempt.memberId(), attempt.issueRequestId(), attempt.epoch(),
                attempt.issuedAt().toEpochMilli(), attempt.expiresAt().toEpochMilli()));
    }

    @Override
    public Optional<IssueReceipt> findRequest(long couponId, String issueRequestId) {
        Object value = run("접수 기록 조회",
                () -> redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), issueRequestId));
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(parseReceipt(couponId, value.toString()));
    }

    /** requests 값 {@code 회원 ID|접수 시각(epoch 밀리초)|결과} */
    private static IssueReceipt parseReceipt(long couponId, String value) {
        String[] parts = value.split("\\|");
        try {
            return new IssueReceipt(Long.parseLong(parts[0]), Instant.ofEpochMilli(Long.parseLong(parts[1])));
        } catch (RuntimeException e) {
            throw new CouponIssueStoreUnavailableException("접수 기록 형식을 읽지 못했다 couponId=" + couponId, e);
        }
    }

    private static <T> T run(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException | IllegalStateException e) {
            throw new CouponIssueStoreUnavailableException(action + "에 Redis를 쓰지 못했다", e);
        }
    }
}
