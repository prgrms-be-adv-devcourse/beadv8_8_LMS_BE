package com.hapbang.coupon.out.redis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponRecoveryStore;
import com.hapbang.coupon.domain.RecoveryRebuildPlan;
import com.hapbang.coupon.domain.RecoveryRedisState;

/**
 * Redis 유실 복구 저장소. 차단·재구축·활성화·프로세스 점검은 {@code resources/lua/recovery-*.lua}가 원자적으로 하고, key는
 * {@link CouponRedisKeys}가 만든다. 스크립트가 Redis 프로세스의 run_id를 직접 읽어(INFO server) 비교하므로 읽기와 쓰기 사이에 재시작이
 * 끼어들지 않는다.
 */
@Component
public class RedisCouponRecoveryStore implements CouponRecoveryStore {

    /** HSCAN 한 번에 가져오는 개수. 측정 전 값이다 */
    private static final int SCAN_COUNT = 500;

    private static final RedisScript<List<Long>> BLOCK = CouponRedisLuaSupport.longListLuaScript("lua/recovery-block.lua");
    private static final RedisScript<Long> REBUILD = CouponRedisLuaSupport.longLuaScript("lua/recovery-rebuild.lua");
    private static final RedisScript<Long> ACTIVATE = CouponRedisLuaSupport.longLuaScript("lua/recovery-activate.lua");
    private static final RedisScript<Long> CHECK = CouponRedisLuaSupport.longLuaScript("lua/recovery-check.lua");

    private final StringRedisTemplate redisTemplate;

    public RedisCouponRecoveryStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public BlockResult block(long couponId, long recoveryId) {
        List<Long> result = run("복구 접수 차단", () -> CouponRedisLuaSupport.runForLongs(redisTemplate, BLOCK,
                List.of(CouponRedisKeys.issueControl(couponId)), recoveryId));
        long code = result.getFirst();
        boolean wasOpen = result.size() > 1 && result.get(1) == 1L;
        boolean stale = result.size() > 2 && result.get(2) == 1L;
        return switch ((int) code) {
            case 1 -> new BlockResult(true, false, wasOpen, stale);
            case 2 -> new BlockResult(true, true, wasOpen, stale);
            case 0 -> new BlockResult(false, false, false);
            default -> throw unavailable("복구 접수 차단을 쓰지 못했다", couponId, code);
        };
    }

    @Override
    public Snapshot snapshot(long couponId) {
        String controlKey = CouponRedisKeys.issueControl(couponId);
        Object storedRunId = run("복구 상태 조회", () -> redisTemplate.opsForHash().get(controlKey, "runId"));
        boolean controlPresent = storedRunId != null
                || Boolean.TRUE.equals(run("복구 상태 조회", () -> redisTemplate.hasKey(controlKey)));
        boolean processChanged = controlPresent && (storedRunId == null || !storedRunId.equals(currentRunId()));
        String stockValue = run("복구 상태 조회", () -> redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId)));
        Long stock = parseStock(couponId, stockValue);
        return new Snapshot(controlPresent, processChanged, stock, scanRequests(couponId));
    }

    @Override
    public RecoveryRedisState redisState(long couponId) {
        String controlKey = CouponRedisKeys.issueControl(couponId);
        Object storedRunId = run("복구 Redis 상태 조회", () -> redisTemplate.opsForHash().get(controlKey, "runId"));
        if (storedRunId == null
                && !Boolean.TRUE.equals(run("복구 Redis 상태 조회", () -> redisTemplate.hasKey(controlKey)))) {
            return RecoveryRedisState.CONTROL_MISSING;
        }
        return storedRunId != null && storedRunId.equals(currentRunId()) ? RecoveryRedisState.INTACT
                : RecoveryRedisState.PROCESS_CHANGED;
    }

    @Override
    public IssueControlState issueControlState(long couponId) {
        String controlKey = CouponRedisKeys.issueControl(couponId);
        List<Object> values = run("issue-control 조회",
                () -> redisTemplate.opsForHash().multiGet(controlKey, List.of("ready", "hold")));
        Object ready = values == null ? null : values.get(0);
        Object hold = values == null ? null : values.get(1);
        // 준비된 issue-control에는 언제나 ready가 있다. 둘 다 없을 때만 key가 있는지 따로 본다
        boolean present = ready != null || hold != null
                || Boolean.TRUE.equals(run("issue-control 조회", () -> redisTemplate.hasKey(controlKey)));
        if (!present) {
            return IssueControlState.missing();
        }
        String holdValue = hold == null || hold.toString().isEmpty() ? null : hold.toString();
        return new IssueControlState(true, "1".equals(String.valueOf(ready)), holdValue);
    }

    @Override
    public RebuildResult rebuild(long couponId, long recoveryId, RecoveryRebuildPlan plan, RebuildControl control) {
        List<String> keys = List.of(CouponRedisKeys.issueControl(couponId), CouponRedisKeys.stock(couponId),
                CouponRedisKeys.users(couponId), CouponRedisKeys.requests(couponId), CouponRedisKeys.soldOut(couponId));
        List<Object> args = new ArrayList<>(List.of(plan.newEpoch(), plan.stock(),
                control.issueStartAt().toEpochMilli(), control.issueEndAt().toEpochMilli(),
                control.keysExpireAt().toEpochMilli(), control.fence(), control.expiryType().name(),
                control.expiryDays() == null ? "" : control.expiryDays().toString(),
                millisOrEmpty(control.fixedExpiresAt()), control.status(), control.now().toEpochMilli(),
                plan.memberIds().size(), recoveryId));
        args.addAll(plan.memberIds());
        plan.requests().forEach((requestId, value) -> {
            args.add(requestId);
            args.add(value);
        });
        long result = run("복구 재구축", () -> CouponRedisLuaSupport.runForLong(redisTemplate, REBUILD, keys,
                args.toArray()));
        return switch ((int) result) {
            case 1 -> RebuildResult.APPLIED;
            case -4 -> RebuildResult.FENCED;
            default -> RebuildResult.REJECTED;
        };
    }

    @Override
    public ActivateResult activate(long couponId, long recoveryId, long newEpoch, String status, long fence) {
        long result = run("복구 재개", () -> CouponRedisLuaSupport.runForLong(redisTemplate, ACTIVATE,
                List.of(CouponRedisKeys.issueControl(couponId)), newEpoch, status, fence, recoveryId));
        return switch ((int) result) {
            case 1 -> ActivateResult.APPLIED;
            case 2 -> ActivateResult.STATUS_SUPERSEDED;
            case 0 -> ActivateResult.MISSING;
            case -1 -> ActivateResult.EPOCH_MISMATCH;
            case -3 -> ActivateResult.PROCESS_CHANGED;
            case -4 -> ActivateResult.FENCED;
            default -> ActivateResult.REJECTED;
        };
    }

    @Override
    public ProcessCheck checkProcess(long couponId) {
        long result = run("Redis 프로세스 점검", () -> CouponRedisLuaSupport.runForLong(redisTemplate, CHECK,
                List.of(CouponRedisKeys.issueControl(couponId))));
        return switch ((int) result) {
            case 1 -> ProcessCheck.SAME;
            case 2 -> ProcessCheck.ADOPTED;
            case -1 -> ProcessCheck.BLOCKED;
            case -2 -> ProcessCheck.ALREADY_BLOCKED;
            case 0 -> ProcessCheck.MISSING;
            default -> throw unavailable("Redis 프로세스를 점검하지 못했다", couponId, result);
        };
    }

    private Map<String, String> scanRequests(long couponId) {
        return run("접수 기록 읽기", () -> {
            Map<String, String> requests = new LinkedHashMap<>();
            ScanOptions options = ScanOptions.scanOptions().count(SCAN_COUNT).build();
            try (Cursor<Map.Entry<Object, Object>> cursor =
                         redisTemplate.opsForHash().scan(CouponRedisKeys.requests(couponId), options)) {
                cursor.forEachRemaining(entry -> requests.put(entry.getKey().toString(), entry.getValue().toString()));
            }
            return requests;
        });
    }

    private String currentRunId() {
        Properties info = run("Redis 프로세스 조회",
                () -> redisTemplate.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("server")));
        String runId = info == null ? null : info.getProperty("run_id");
        if (runId == null || runId.isBlank()) {
            throw new CouponIssueStoreUnavailableException("Redis 프로세스 run_id를 읽지 못했다", null);
        }
        return runId;
    }

    private static Long parseStock(long couponId, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new CouponIssueStoreUnavailableException("재고 값을 읽지 못했다 couponId=" + couponId, e);
        }
    }

    private static String millisOrEmpty(Instant instant) {
        return instant == null ? "" : String.valueOf(instant.toEpochMilli());
    }

    private static CouponIssueStoreUnavailableException unavailable(String message, long couponId, long result) {
        return new CouponIssueStoreUnavailableException(message + " couponId=" + couponId + ", result=" + result, null);
    }

    private static <T> T run(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException | IllegalStateException e) {
            throw new CouponIssueStoreUnavailableException(action + "에 Redis를 쓰지 못했다", e);
        }
    }
}
