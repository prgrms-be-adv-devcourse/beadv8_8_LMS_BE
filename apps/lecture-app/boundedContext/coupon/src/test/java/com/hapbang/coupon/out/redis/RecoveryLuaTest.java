package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.hapbang.coupon.app.port.CouponIssueStore.IssueAttempt;
import com.hapbang.coupon.app.port.CouponIssueStore.IssueControl;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.app.port.CouponRecoveryStore.ActivateResult;
import com.hapbang.coupon.app.port.CouponRecoveryStore.BlockResult;
import com.hapbang.coupon.app.port.CouponRecoveryStore.IssueControlState;
import com.hapbang.coupon.app.port.CouponRecoveryStore.ProcessCheck;
import com.hapbang.coupon.app.port.CouponRecoveryStore.RebuildControl;
import com.hapbang.coupon.app.port.CouponRecoveryStore.RebuildResult;
import com.hapbang.coupon.app.port.CouponRecoveryStore.Snapshot;
import com.hapbang.coupon.domain.ExpiryType;
import com.hapbang.coupon.domain.RecoveryRebuildPlan;
import com.hapbang.coupon.domain.RecoveryRedisState;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * 복구 Lua(recovery-block·rebuild·activate·check)와 prepare.lua의 runId 기록을 실제 Redis에서 본다. 테스트끼리 Redis를 같이 쓰므로 쿠폰
 * ID를 테스트마다 새로 만들고 자기가 만든 key만 지운다. 프로세스 변경은 issue-control의 runId를 다른 값으로 바꿔 흉내 낸다.
 */
@SpringJUnitConfig(RecoveryLuaTest.Config.class)
class RecoveryLuaTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final Instant START = NOW.minus(Duration.ofHours(1));
    private static final Instant END = NOW.plus(Duration.ofHours(1));
    private static final Instant KEYS_EXPIRE_AT = END.plus(Duration.ofDays(7));
    private static final long RECOVERY_ID = 7L;
    private static final String OLD_REQUEST = "1|" + NOW.toEpochMilli() + "|ACCEPTED|" + END.toEpochMilli();

    @Autowired
    private RedisCouponIssueStore issueStore;

    @Autowired
    private RedisCouponRecoveryStore recoveryStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    private long newCouponId() {
        long couponId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        createdKeys.addAll(List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId),
                CouponRedisKeys.soldOut(couponId)));
        return couponId;
    }

    /** 접수를 연 쿠폰을 이 테스트의 복구로 막는다(재구축 토큰을 쓴다) */
    private long blockedCoupon(long stock) {
        long couponId = openCoupon(stock);
        recoveryStore.block(couponId, RECOVERY_ID);
        return couponId;
    }

    /** 준비(fence 0)하고 DB OPEN 커밋(version 1)을 흉내 내 접수를 연다 */
    private long openCoupon(long stock) {
        long couponId = newCouponId();
        issueStore.prepare(new IssueControl(couponId, 1L, stock, START, END, KEYS_EXPIRE_AT, 0, ExpiryType.RELATIVE,
                30, null));
        issueStore.allowIssue(couponId, 1);
        return couponId;
    }

    private long issue(long couponId, long memberId, String requestId) {
        return issueStore.tryIssue(new IssueAttempt(couponId, memberId, requestId, NOW)).code();
    }

    private Object control(long couponId, String field) {
        return redisTemplate.opsForHash().get(CouponRedisKeys.issueControl(couponId), field);
    }

    private void setControl(long couponId, String field, String value) {
        redisTemplate.opsForHash().put(CouponRedisKeys.issueControl(couponId), field, value);
    }

    private String currentRunId() {
        Properties info = redisTemplate.execute((RedisCallback<Properties>) c -> c.serverCommands().info("server"));
        return info.getProperty("run_id");
    }

    private static RebuildControl rebuildControl(String status, long fence) {
        return new RebuildControl(START, END, KEYS_EXPIRE_AT, fence, ExpiryType.RELATIVE, 30, null, status, NOW);
    }

    @Test
    void 준비는_Redis_프로세스_run_id를_기록하고_복구_차단은_접수를_막는다() {
        long couponId = openCoupon(10);
        assertThat(control(couponId, "runId")).isEqualTo(currentRunId());
        assertThat(issue(couponId, 100L, "req-before")).isEqualTo(1L);

        BlockResult result = recoveryStore.block(couponId, RECOVERY_ID);

        assertThat(result).isEqualTo(new BlockResult(true, false, true));
        assertThat(control(couponId, "ready")).isEqualTo("0");
        assertThat(control(couponId, "recoveryId")).isEqualTo(String.valueOf(RECOVERY_ID));
        // 이미 막힌 쿠폰을 다시 막으면 접수가 열려 있지 않았다고 알려 준다(차단 시각을 다시 잡지 않는다)
        assertThat(recoveryStore.block(couponId, RECOVERY_ID)).isEqualTo(new BlockResult(true, false, false));
        assertThat(control(couponId, "hold")).isEqualTo("RECOVERY");
        assertThat(control(couponId, "status")).isEqualTo("OPEN");
        assertThat(issue(couponId, 200L, "req-after")).isEqualTo(-2L);
        assertThat(redisTemplate.opsForHash().hasKey(CouponRedisKeys.requests(couponId), "req-before")).isTrue();
        // 열기·재개(allow-issue.lua)는 ready≠1이라 접수를 열지 못한다
        assertThatThrownBy(() -> issueStore.allowIssue(couponId, 5)).isInstanceOf(CouponIssueStoreUnavailableException.class);
    }

    @Test
    void issue_control이_없으면_차단은_아무것도_쓰지_않고_다른_프로세스의_기록이면_알려_준다() {
        long missing = newCouponId();
        assertThat(recoveryStore.block(missing, RECOVERY_ID)).isEqualTo(new BlockResult(false, false, false));
        assertThat(redisTemplate.hasKey(CouponRedisKeys.issueControl(missing))).isFalse();

        long couponId = openCoupon(10);
        setControl(couponId, "runId", "another-process");
        assertThat(recoveryStore.block(couponId, RECOVERY_ID)).isEqualTo(new BlockResult(true, true, true));
        assertThat(recoveryStore.snapshot(couponId).processChanged()).isTrue();
    }

    @Test
    void 재구축은_옛_기록을_지우고_DB_확정_결과로_한_번에_다시_만들며_접수는_막힌_채다() {
        long couponId = openCoupon(10);
        issue(couponId, 1L, "req-old");
        recoveryStore.block(couponId, RECOVERY_ID);
        redisTemplate.opsForSet().add(CouponRedisKeys.users(couponId), "999");
        RecoveryRebuildPlan plan = new RecoveryRebuildPlan(2L, 8L, List.of(100L, 200L),
                Map.of("req-a", "100|1|ACCEPTED|2|e1|PUBLISHED", "req-b", "200|1|ACCEPTED|2|PUBLISHED",
                        "req-c", "300|1|ACCEPTED|2|e1|PUBLISHED|COMPENSATED"));

        assertThat(recoveryStore.rebuild(couponId, RECOVERY_ID, plan, rebuildControl("OPEN", 3)))
                .isEqualTo(RebuildResult.APPLIED);

        // 요청 결과는 받은 값 그대로 쓴다: 접수 epoch(DB epoch)가 있는 새 형식도, 없는 옛 형식도 받는다
        assertThat(redisTemplate.opsForHash().entries(CouponRedisKeys.requests(couponId))).isEqualTo(plan.requests());

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("8");
        assertThat(redisTemplate.opsForSet().members(CouponRedisKeys.users(couponId))).containsExactlyInAnyOrder("100", "200");
        assertThat(redisTemplate.opsForHash().entries(CouponRedisKeys.requests(couponId))).containsOnlyKeys("req-a", "req-b", "req-c");
        assertThat(redisTemplate.hasKey(CouponRedisKeys.soldOut(couponId))).isFalse();
        assertThat(control(couponId, "ready")).isEqualTo("0");
        assertThat(control(couponId, "hold")).isEqualTo("RECOVERY");
        assertThat(control(couponId, "recoveryId")).isEqualTo(String.valueOf(RECOVERY_ID));
        assertThat(control(couponId, "epoch")).isEqualTo("2");
        assertThat(control(couponId, "fence")).isEqualTo("3");
        assertThat(control(couponId, "runId")).isEqualTo(currentRunId());
        assertThat(control(couponId, "expiryDays")).isEqualTo("30");
        for (String key : List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId))) {
            assertThat(redisTemplate.getExpire(key)).as(key).isPositive();
        }
        assertThat(issue(couponId, 400L, "req-new")).isEqualTo(-2L);
    }

    @Test
    void 재고가_0이면_매진_표시를_남기고_저장된_종료와_더_큰_fence는_되돌리지_않는다() {
        long couponId = blockedCoupon(1);
        setControl(couponId, "status", "CLOSED");
        setControl(couponId, "fence", "9");

        recoveryStore.rebuild(couponId, RECOVERY_ID, new RecoveryRebuildPlan(2L, 0L, List.of(100L),
                Map.of("req-a", "100|1|ACCEPTED|2|PUBLISHED")), rebuildControl("OPEN", 3));

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.soldOut(couponId))).isEqualTo("1");
        assertThat(control(couponId, "status")).isEqualTo("CLOSED");
        assertThat(control(couponId, "fence")).isEqualTo("9");
    }

    @Test
    void 재구축_인수가_잘못되면_아무것도_지우거나_쓰지_않는다() {
        long couponId = openCoupon(10);
        issue(couponId, 1L, "req-old");
        recoveryStore.block(couponId, RECOVERY_ID);
        RecoveryRebuildPlan plan = new RecoveryRebuildPlan(2L, 8L, List.of(100L), Map.of("req-a", "깨진 값"));

        assertThat(recoveryStore.rebuild(couponId, RECOVERY_ID, plan, rebuildControl("OPEN", 3)))
                .isEqualTo(RebuildResult.REJECTED);
        // 접수 epoch 필드는 만료 시각 바로 뒤에만 오고, 표식은 대문자 낱말만 받는다
        for (String value : List.of("100|1|ACCEPTED|2|PUBLISHED|e1", "100|1|ACCEPTED|2|ex", "100|1|ACCEPTED|2|e1|done")) {
            RecoveryRebuildPlan malformed = new RecoveryRebuildPlan(2L, 8L, List.of(100L), Map.of("req-a", value));
            assertThat(recoveryStore.rebuild(couponId, RECOVERY_ID, malformed, rebuildControl("OPEN", 3)))
                    .as(value).isEqualTo(RebuildResult.REJECTED);
        }
        // key 만료 시각이 지난 재구축도 쓰지 않는다
        RecoveryRebuildPlan valid = new RecoveryRebuildPlan(2L, 8L, List.of(), Map.of());
        RebuildControl expired = new RebuildControl(START, END, KEYS_EXPIRE_AT, 3, ExpiryType.RELATIVE, 30, null, "OPEN",
                KEYS_EXPIRE_AT.plusSeconds(1));
        assertThat(recoveryStore.rebuild(couponId, RECOVERY_ID, valid, expired)).isEqualTo(RebuildResult.REJECTED);

        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(redisTemplate.opsForHash().entries(CouponRedisKeys.requests(couponId))).containsOnlyKeys("req-old");
        assertThat(control(couponId, "epoch")).isEqualTo("1");
        assertThat(control(couponId, "ready")).isEqualTo("0");
    }

    @Test
    void 재개는_재구축한_epoch만_활성화하고_더_큰_fence의_쓰기가_있으면_status는_그대로_둔다() {
        long couponId = blockedCoupon(10);
        recoveryStore.rebuild(couponId, RECOVERY_ID, new RecoveryRebuildPlan(2L, 10L, List.of(), Map.of()),
                rebuildControl("OPEN", 3));

        assertThat(recoveryStore.activate(couponId, RECOVERY_ID, 3L, "OPEN", 4)).isEqualTo(ActivateResult.EPOCH_MISMATCH);
        assertThat(control(couponId, "ready")).isEqualTo("0");

        setControl(couponId, "status", "SUSPENDED");
        setControl(couponId, "fence", "5");
        assertThat(recoveryStore.activate(couponId, RECOVERY_ID, 2L, "OPEN", 5)).isEqualTo(ActivateResult.STATUS_SUPERSEDED);
        assertThat(control(couponId, "ready")).isEqualTo("1");
        assertThat(control(couponId, "hold")).isNull();
        assertThat(control(couponId, "status")).isEqualTo("SUSPENDED");
        assertThat(issue(couponId, 100L, "req-new")).isEqualTo(-2L);

        assertThat(recoveryStore.activate(couponId, RECOVERY_ID, 2L, "OPEN", 6)).isEqualTo(ActivateResult.APPLIED);
        assertThat(control(couponId, "status")).isEqualTo("OPEN");
        assertThat(control(couponId, "fence")).isEqualTo("6");
        assertThat(issue(couponId, 100L, "req-new")).isEqualTo(1L);
    }

    @Test
    void 재개는_issue_control의_복구_ID가_이_복구일_때만_열고_이미_연_재개를_다시_하면_연_결과로_답한다() {
        long couponId = blockedCoupon(10);
        recoveryStore.rebuild(couponId, RECOVERY_ID, new RecoveryRebuildPlan(2L, 10L, List.of(), Map.of()),
                rebuildControl("OPEN", 3));

        // 다른 복구의 재개는 열지 않는다
        assertThat(recoveryStore.activate(couponId, RECOVERY_ID + 1, 2L, "OPEN", 4)).isEqualTo(ActivateResult.FENCED);
        assertThat(control(couponId, "ready")).isEqualTo("0");
        assertThat(control(couponId, "hold")).isEqualTo("RECOVERY");

        assertThat(recoveryStore.activate(couponId, RECOVERY_ID, 2L, "OPEN", 4)).isEqualTo(ActivateResult.APPLIED);
        assertThat(issue(couponId, 100L, "req-after-resume")).isEqualTo(1L);
        // 응답을 못 받아 다시 재개해도(같은 epoch·복구 ID, 커밋된 더 큰 fence) 연 결과로 답하고 접수 기록을 건드리지 않는다
        assertThat(recoveryStore.activate(couponId, RECOVERY_ID, 2L, "OPEN", 5)).isEqualTo(ActivateResult.APPLIED);
        assertThat(control(couponId, "ready")).isEqualTo("1");
        assertThat(control(couponId, "fence")).isEqualTo("5");
        assertThat(redisTemplate.opsForHash().hasKey(CouponRedisKeys.requests(couponId), "req-after-resume")).isTrue();

        // 복구 ID가 없는 issue-control도 열지 않는다
        redisTemplate.opsForHash().delete(CouponRedisKeys.issueControl(couponId), "recoveryId");
        assertThat(recoveryStore.activate(couponId, RECOVERY_ID, 2L, "OPEN", 6)).isEqualTo(ActivateResult.FENCED);
        assertThat(control(couponId, "fence")).isEqualTo("5");
        // issue-control이 없으면 쓰지 않는다
        assertThat(recoveryStore.activate(newCouponId(), RECOVERY_ID, 2L, "OPEN", 6)).isEqualTo(ActivateResult.MISSING);
    }

    @Test
    void 더_작은_복구_ID의_차단은_접수만_막고_recoveryId를_바꾸지_않으며_더_큰_ID는_바꾼다() {
        long couponId = openCoupon(10);
        assertThat(recoveryStore.block(couponId, 10L)).isEqualTo(new BlockResult(true, false, true, false));
        assertThat(control(couponId, "recoveryId")).isEqualTo("10");

        // 9 < 10: 늦게 도착한 이전 복구. 접수는 막고(이미 막혀 있다) recoveryId는 그대로
        assertThat(recoveryStore.block(couponId, 9L)).isEqualTo(new BlockResult(true, false, false, true));
        assertThat(control(couponId, "recoveryId")).isEqualTo("10");
        // 자릿수가 다른 비교도 수 크기로 한다(문자열 "9" > "10"이 아니다)
        assertThat(recoveryStore.block(couponId, 100L).stale()).isFalse();
        assertThat(control(couponId, "recoveryId")).isEqualTo("100");
        assertThat(recoveryStore.block(couponId, 100L).stale()).isFalse();
        assertThat(recoveryStore.block(couponId, Long.MAX_VALUE).stale()).isFalse();
        assertThat(control(couponId, "recoveryId")).isEqualTo(String.valueOf(Long.MAX_VALUE));
        assertThat(recoveryStore.block(couponId, Long.MAX_VALUE - 1).stale()).isTrue();
        assertThat(control(couponId, "recoveryId")).isEqualTo(String.valueOf(Long.MAX_VALUE));
    }

    @Test
    void 이전_복구의_차단이_늦게_도착해도_새_복구는_재구축_뒤_재개된다() {
        long couponId = openCoupon(10);
        long olderRecovery = RECOVERY_ID;
        long newerRecovery = RECOVERY_ID + 1;
        recoveryStore.block(couponId, newerRecovery);
        RecoveryRebuildPlan plan = new RecoveryRebuildPlan(2L, 10L, List.of(), Map.of());
        assertThat(recoveryStore.rebuild(couponId, newerRecovery, plan, rebuildControl("OPEN", 3)))
                .isEqualTo(RebuildResult.APPLIED);

        // 이전 복구 A의 지연된 차단(시작·경계 확정 재차단)
        assertThat(recoveryStore.block(couponId, olderRecovery).stale()).isTrue();

        assertThat(recoveryStore.activate(couponId, newerRecovery, 2L, "OPEN", 4)).isEqualTo(ActivateResult.APPLIED);
        assertThat(issue(couponId, 100L, "req-after-resume")).isEqualTo(1L);
        // 재개 뒤에 도착한 지연 차단은 접수를 막는 쪽으로만 쓴다(안전). 새 복구 ID는 그대로다
        assertThat(recoveryStore.block(couponId, olderRecovery)).isEqualTo(new BlockResult(true, false, true, true));
        assertThat(control(couponId, "ready")).isEqualTo("0");
        assertThat(control(couponId, "recoveryId")).isEqualTo(String.valueOf(newerRecovery));
    }

    @Test
    void Redis_상태는_issue_control이_없거나_runId가_다르거나_없으면_유실로_읽는다() {
        long couponId = openCoupon(10);
        assertThat(recoveryStore.redisState(couponId)).isEqualTo(RecoveryRedisState.INTACT);

        setControl(couponId, "runId", "another-process");
        assertThat(recoveryStore.redisState(couponId)).isEqualTo(RecoveryRedisState.PROCESS_CHANGED);
        redisTemplate.opsForHash().delete(CouponRedisKeys.issueControl(couponId), "runId");
        assertThat(recoveryStore.redisState(couponId)).isEqualTo(RecoveryRedisState.PROCESS_CHANGED);

        assertThat(recoveryStore.redisState(newCouponId())).isEqualTo(RecoveryRedisState.CONTROL_MISSING);
    }

    @Test
    void 재구축_뒤_Redis_프로세스가_바뀌었으면_재개하지_않고_다른_사유의_보류는_풀지_않는다() {
        long couponId = blockedCoupon(10);
        recoveryStore.rebuild(couponId, RECOVERY_ID, new RecoveryRebuildPlan(2L, 10L, List.of(), Map.of()),
                rebuildControl("OPEN", 3));
        setControl(couponId, "runId", "another-process");

        assertThat(recoveryStore.activate(couponId, RECOVERY_ID, 2L, "OPEN", 4))
                .isEqualTo(ActivateResult.PROCESS_CHANGED);
        assertThat(control(couponId, "ready")).isEqualTo("0");

        setControl(couponId, "runId", currentRunId());
        setControl(couponId, "hold", "BACKLOG");
        recoveryStore.activate(couponId, RECOVERY_ID, 2L, "OPEN", 4);
        assertThat(control(couponId, "hold")).isEqualTo("BACKLOG");
    }

    @Test
    void 프로세스_점검은_run_id가_다르면_접수를_막고_자동으로_다시_열지_않는다() {
        long couponId = openCoupon(10);
        assertThat(recoveryStore.checkProcess(couponId)).isEqualTo(ProcessCheck.SAME);

        setControl(couponId, "runId", "another-process");
        assertThat(recoveryStore.checkProcess(couponId)).isEqualTo(ProcessCheck.BLOCKED);
        assertThat(control(couponId, "ready")).isEqualTo("0");
        assertThat(control(couponId, "hold")).isEqualTo("RECOVERY");
        assertThat(recoveryStore.checkProcess(couponId)).isEqualTo(ProcessCheck.ALREADY_BLOCKED);
        assertThat(issue(couponId, 100L, "req-new")).isEqualTo(-2L);

        redisTemplate.opsForHash().delete(CouponRedisKeys.issueControl(couponId), "runId");
        assertThat(recoveryStore.checkProcess(couponId)).isEqualTo(ProcessCheck.ADOPTED);
        assertThat(control(couponId, "runId")).isEqualTo(currentRunId());

        assertThat(recoveryStore.checkProcess(newCouponId())).isEqualTo(ProcessCheck.MISSING);
    }

    @Test
    void 상태_읽기는_재고와_접수_기록_전부를_돌려준다() {
        long couponId = openCoupon(10);
        issue(couponId, 1L, "req-a");
        issue(couponId, 2L, "req-b");

        Snapshot snapshot = recoveryStore.snapshot(couponId);

        assertThat(snapshot.controlPresent()).isTrue();
        assertThat(snapshot.processChanged()).isFalse();
        assertThat(snapshot.stock()).isEqualTo(8L);
        assertThat(snapshot.requests()).containsOnlyKeys("req-a", "req-b");
        assertThat(recoveryStore.issueControlState(couponId)).isEqualTo(new IssueControlState(true, true, null, KEYS_EXPIRE_AT));

        long lost = newCouponId();
        Snapshot empty = recoveryStore.snapshot(lost);
        assertThat(empty).isEqualTo(new Snapshot(false, false, null, Map.of()));
        assertThat(recoveryStore.issueControlState(lost)).isEqualTo(IssueControlState.missing());
    }

    @Test
    void issue_control_상태는_ready와_hold와_key_만료_시각을_돌려준다() {
        long couponId = openCoupon(10);
        setControl(couponId, "hold", "BACKLOG");
        assertThat(recoveryStore.issueControlState(couponId))
                .isEqualTo(new IssueControlState(true, true, "BACKLOG", KEYS_EXPIRE_AT));

        recoveryStore.block(couponId, RECOVERY_ID);

        IssueControlState blocked = recoveryStore.issueControlState(couponId);
        assertThat(blocked).isEqualTo(new IssueControlState(true, false, "RECOVERY", KEYS_EXPIRE_AT));
        assertThat(blocked.recoveryHold()).isTrue();
    }

    @Test
    void 재개_뒤_늦게_도착한_재구축은_토큰이_맞아도_ready가_1이라_거절되고_신규_접수를_지우지_않는다() {
        long couponId = blockedCoupon(10);
        RecoveryRebuildPlan plan = new RecoveryRebuildPlan(2L, 10L, List.of(), Map.of());
        assertThat(recoveryStore.rebuild(couponId, RECOVERY_ID, plan, rebuildControl("OPEN", 3)))
                .isEqualTo(RebuildResult.APPLIED);
        recoveryStore.activate(couponId, RECOVERY_ID, 2L, "OPEN", 4);
        assertThat(issue(couponId, 100L, "req-after-resume")).isEqualTo(1L);

        assertThat(recoveryStore.rebuild(couponId, RECOVERY_ID, plan, rebuildControl("OPEN", 3)))
                .isEqualTo(RebuildResult.FENCED);

        assertThat(redisTemplate.opsForHash().hasKey(CouponRedisKeys.requests(couponId), "req-after-resume")).isTrue();
        assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId))).isEqualTo("9");
        assertThat(control(couponId, "ready")).isEqualTo("1");
    }

    @Test
    void 다른_복구가_막았거나_복구_차단_없이_열린_issue_control은_재구축하지_않고_issue_control이_없으면_다시_만든다() {
        RecoveryRebuildPlan plan = new RecoveryRebuildPlan(2L, 10L, List.of(), Map.of());
        long otherRecovery = blockedCoupon(10);
        setControl(otherRecovery, "recoveryId", String.valueOf(RECOVERY_ID + 1));
        assertThat(recoveryStore.rebuild(otherRecovery, RECOVERY_ID, plan, rebuildControl("OPEN", 3)))
                .isEqualTo(RebuildResult.FENCED);
        assertThat(control(otherRecovery, "epoch")).isEqualTo("1");

        long open = openCoupon(10);
        assertThat(recoveryStore.rebuild(open, RECOVERY_ID, plan, rebuildControl("OPEN", 3)))
                .isEqualTo(RebuildResult.FENCED);
        assertThat(control(open, "ready")).isEqualTo("1");

        long lost = newCouponId();
        assertThat(recoveryStore.rebuild(lost, RECOVERY_ID, plan, rebuildControl("OPEN", 3)))
                .isEqualTo(RebuildResult.APPLIED);
        assertThat(control(lost, "recoveryId")).isEqualTo(String.valueOf(RECOVERY_ID));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RedisTestcontainersConfig.class, RedisCouponIssueStore.class, RedisCouponRecoveryStore.class})
    @ImportAutoConfiguration(DataRedisAutoConfiguration.class)
    static class Config {
    }
}
