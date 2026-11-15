package com.hapbang.coupon.out.redis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponReconciliationStore;
import com.hapbang.coupon.domain.IssueHoldReason;
import com.hapbang.coupon.domain.IssueReconciliationRule.MissingUser;
import com.hapbang.coupon.domain.IssueReconciliationRule.SoldOutRepair;

/**
 * Redis 대사 저장소. 읽기·보류·고치기·정리는 {@code resources/lua/reconcile-*.lua}가 원자적으로 하고, key는 {@link CouponRedisKeys}가
 * 만든다. 정리 대상 요청 기록 지우기만 HDEL을 바로 쓴다(DB 최종 결과가 확인된 요청이라 그 사이 바뀌어도 지워도 된다).
 */
@Component
public class RedisCouponReconciliationStore implements CouponReconciliationStore {

    /** HDEL 한 번에 지우는 요청 수 */
    static final int REMOVE_BATCH = 500;

    private static final RedisScript<List<Object>> SNAPSHOT =
            CouponRedisLuaSupport.listLuaScript("lua/reconcile-snapshot.lua");
    private static final RedisScript<Long> HOLD = CouponRedisLuaSupport.longLuaScript("lua/reconcile-hold.lua");
    private static final RedisScript<List<Long>> REPAIR =
            CouponRedisLuaSupport.longListLuaScript("lua/reconcile-repair.lua");
    private static final RedisScript<Long> CLEANUP = CouponRedisLuaSupport.longLuaScript("lua/reconcile-cleanup.lua");

    private final StringRedisTemplate redisTemplate;

    public RedisCouponReconciliationStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Snapshot snapshot(long couponId) {
        List<Object> result = run("대사 읽기", () -> redisTemplate.execute(SNAPSHOT, couponKeys(couponId)));
        if (result == null || result.isEmpty() || !Long.valueOf(1).equals(result.get(0)) || result.size() != 6) {
            throw new CouponIssueStoreUnavailableException(
                    "대사용 Redis 기록을 읽지 못했다 couponId=" + couponId + ", result=" + head(result), null);
        }
        try {
            Map<String, String> control = pairs(result.get(1));
            String stock = (String) result.get(2);
            boolean soldOut = "1".equals(result.get(3));
            Set<Long> users = new HashSet<>();
            for (Object member : (List<?>) result.get(4)) {
                users.add(Long.parseLong((String) member));
            }
            return new Snapshot(!control.isEmpty(), "1".equals(control.get("ready")), control.get("status"),
                    parseLongOrNull(control.get("epoch")), IssueHoldReason.parse(control.get("hold")),
                    parseLongOrNull(stock), soldOut, users, pairs(result.get(5)));
        } catch (RuntimeException e) {
            throw new CouponIssueStoreUnavailableException("대사용 Redis 기록 형식을 읽지 못했다 couponId=" + couponId, e);
        }
    }

    @Override
    public boolean hold(long couponId, IssueHoldReason reason) {
        List<Object> args = new ArrayList<>(List.of("SET", reason.name()));
        reason.lighter().forEach(lighter -> args.add(lighter.name()));
        return writeHold(couponId, args.toArray());
    }

    @Override
    public boolean releaseHold(long couponId, IssueHoldReason reason) {
        return writeHold(couponId, "CLEAR", reason.name());
    }

    @Override
    public IssueHoldReason readHold(long couponId) {
        Object hold = run("신규 접수 보류 읽기",
                () -> redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), "hold"));
        return IssueHoldReason.parse((String) hold);
    }

    private boolean writeHold(long couponId, Object... args) {
        long result = run("신규 접수 보류", () -> CouponRedisLuaSupport.runForLong(redisTemplate, HOLD,
                List.of(CouponRedisKeys.issueControl(couponId)), args));
        if (result == -9) {
            throw new CouponIssueStoreUnavailableException("신규 접수 보류를 쓰지 못했다 couponId=" + couponId, null);
        }
        return result == 1;
    }

    @Override
    public long removeRequests(long couponId, List<String> issueRequestIds) {
        String requestsKey = CouponRedisKeys.requests(couponId);
        long removed = 0;
        for (int from = 0; from < issueRequestIds.size(); from += REMOVE_BATCH) {
            Object[] fields = issueRequestIds.subList(from, Math.min(from + REMOVE_BATCH, issueRequestIds.size()))
                    .toArray();
            Long count = run("요청 기록 정리", () -> redisTemplate.opsForHash().delete(requestsKey, fields));
            removed += count == null ? 0 : count;
        }
        return removed;
    }

    @Override
    public RepairResult repair(long couponId, SoldOutRepair soldOut, List<MissingUser> missingUsers) {
        List<Object> args = new ArrayList<>();
        args.add(soldOut.name());
        for (MissingUser user : missingUsers) {
            args.add(user.memberId());
            args.add(user.requestId() == null ? "" : user.requestId());
        }
        List<Long> result = run("대사 고치기", () -> CouponRedisLuaSupport.runForLongs(redisTemplate, REPAIR,
                couponKeys(couponId), args.toArray()));
        if (result.size() != 2 || result.get(0) == null || result.get(0) < -1 || result.get(1) == null) {
            throw new CouponIssueStoreUnavailableException(
                    "매진 표시·회원 기록을 고치지 못했다 couponId=" + couponId + ", result=" + result, null);
        }
        return new RepairResult(result.get(0).intValue(), result.get(1));
    }

    @Override
    public boolean cleanUp(long couponId) {
        long result = run("쿠폰 기록 정리",
                () -> CouponRedisLuaSupport.runForLong(redisTemplate, CLEANUP, couponKeys(couponId)));
        if (result == -9) {
            throw new CouponIssueStoreUnavailableException("쿠폰 Redis 기록을 정리하지 못했다 couponId=" + couponId, null);
        }
        return result == 1;
    }

    /** reconcile-*.lua의 KEYS 순서: issue-control, stock, users, requests, sold_out */
    private static List<String> couponKeys(long couponId) {
        return List.of(CouponRedisKeys.issueControl(couponId), CouponRedisKeys.stock(couponId),
                CouponRedisKeys.users(couponId), CouponRedisKeys.requests(couponId), CouponRedisKeys.soldOut(couponId));
    }

    /** HGETALL 평탄 배열(field, value, ...)을 Map으로 */
    private static Map<String, String> pairs(Object flat) {
        List<?> list = (List<?>) flat;
        Map<String, String> map = new HashMap<>(list.size());
        for (int i = 0; i + 1 < list.size(); i += 2) {
            map.put((String) list.get(i), (String) list.get(i + 1));
        }
        return map;
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Object head(List<Object> result) {
        return result == null || result.isEmpty() ? result : result.get(0);
    }

    private static <T> T run(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException | IllegalStateException e) {
            throw new CouponIssueStoreUnavailableException(action + "에 Redis를 쓰지 못했다", e);
        }
    }
}
