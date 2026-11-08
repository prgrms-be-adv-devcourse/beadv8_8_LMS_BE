package com.hapbang.coupon.app;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.coupon.app.port.CouponRecoveryStore;
import com.hapbang.coupon.app.port.CouponRecoveryStore.ProcessCheck;
import com.hapbang.coupon.config.CouponClockConfig;
import com.hapbang.coupon.domain.CouponStatus;
import com.hapbang.coupon.out.RecoveryLookupRepository;
import com.hapbang.coupon.out.redis.CouponRedisProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 프로세스 변경 점검. 발급이 열렸거나 중단된(OPEN·SUSPENDED) 쿠폰마다 issue-control의 runId를 지금 Redis 프로세스의 run_id와
 * 비교한다.
 * <ul>
 *     <li>다르면 재시작으로 AOF everysec 사이의 접수 기록이 사라졌을 수 있으므로 신규 접수를 막고(ready=0, hold=RECOVERY) ERROR로 알린다.
 *     자동으로 다시 열지 않는다. 관리자가 복구 절차(경계 확정 → 결과 확정 → 재구축 → 재개)로 연다.</li>
 *     <li>issue-control이 없으면(유실) 막을 것이 없다(issue.lua가 이미 -2로 거절한다). ERROR로 알리고, 상태 조회는 UNKNOWN을 돌려준다.</li>
 *     <li>runId가 없는 issue-control(이 점검 전에 준비한 key)은 지금 값을 기록하고 넘어간다.</li>
 * </ul>
 * 지표 {@code coupon.recovery.redis_check{result=blocked|missing|adopted}}: 점검 결과별 건수. blocked·missing이 0보다 크면 복구가 필요하다.
 * 쿠폰마다 예외를 잡아 한 쿠폰의 실패가 회차를 멈추지 않게 한다.
 */
@Slf4j
@Service
public class CouponRedisProcessCheckUseCase {

    static final String METRIC = "coupon.recovery.redis_check";

    private final RecoveryLookupRepository recoveryLookupRepository;
    private final CouponRecoveryStore recoveryStore;
    private final CouponRedisProperties redisProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public CouponRedisProcessCheckUseCase(RecoveryLookupRepository recoveryLookupRepository,
                                          CouponRecoveryStore recoveryStore, CouponRedisProperties redisProperties,
                                          ObjectProvider<MeterRegistry> meterRegistry,
                                          @Qualifier(CouponClockConfig.CLOCK) Clock clock) {
        this.recoveryLookupRepository = recoveryLookupRepository;
        this.recoveryStore = recoveryStore;
        this.redisProperties = redisProperties;
        this.meterRegistry = meterRegistry.getIfAvailable(SimpleMeterRegistry::new);
        this.clock = clock;
    }

    /** 한 회차. 막은 쿠폰 수를 돌려준다 */
    public int checkAll() {
        List<Long> couponIds = recoveryLookupRepository.findRedisReadyCouponIds(
                List.of(CouponStatus.OPEN, CouponStatus.SUSPENDED),
                clock.instant().minus(redisProperties.keysExpireMargin()));
        int blocked = 0;
        for (long couponId : couponIds) {
            try {
                if (check(couponId) == ProcessCheck.BLOCKED) {
                    blocked++;
                }
            } catch (RuntimeException exception) {
                log.warn("Redis 프로세스 점검을 다음 회차로 미뤘다 couponId={}", couponId, exception);
            }
        }
        return blocked;
    }

    private ProcessCheck check(long couponId) {
        ProcessCheck result = recoveryStore.checkProcess(couponId);
        switch (result) {
            case BLOCKED -> {
                meterRegistry.counter(METRIC, "result", "blocked").increment();
                log.error("Redis 프로세스가 바뀌어 쿠폰 발급 접수를 막았다. 관리자 복구가 필요하다 couponId={}", couponId);
            }
            case MISSING -> {
                meterRegistry.counter(METRIC, "result", "missing").increment();
                log.error("발급 중인 쿠폰의 issue-control이 Redis에 없다(유실). 관리자 복구가 필요하다 couponId={}", couponId);
            }
            case ADOPTED -> {
                meterRegistry.counter(METRIC, "result", "adopted").increment();
                log.info("issue-control에 Redis 프로세스 run_id를 처음 기록했다 couponId={}", couponId);
            }
            case SAME, ALREADY_BLOCKED -> {
            }
        }
        return result;
    }
}
