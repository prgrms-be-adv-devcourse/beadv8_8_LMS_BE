package com.hapbang.coupon.out.redis;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.hapbang.coupon.app.port.CouponCompensationStore;
import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.domain.CouponDomainException;
import com.hapbang.coupon.domain.IssueCompensation;

/**
 * Redis 영구 거절 보상 저장소. 판정·쓰기는 {@code lua/compensate.lua}가 원자적으로 하고, key는 {@link CouponRedisKeys}가 만든다.
 * 스크립트 오류(-9: 인수·key 타입·접수 기록 형식)와 연결 실패는 {@link CouponIssueStoreUnavailableException}이다(성공으로 보지 않는다).
 */
@Component
public class RedisCouponCompensationStore implements CouponCompensationStore {

    private static final RedisScript<Long> COMPENSATE = CouponRedisLuaSupport.longLuaScript("lua/compensate.lua");

    private final StringRedisTemplate redisTemplate;

    public RedisCouponCompensationStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public IssueCompensation compensate(long couponId, String issueRequestId, long memberId, long epoch) {
        List<String> keys = List.of(
                CouponRedisKeys.issueControl(couponId),
                CouponRedisKeys.stock(couponId),
                CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId),
                CouponRedisKeys.soldOut(couponId));
        long code;
        try {
            code = CouponRedisLuaSupport.runForLong(redisTemplate, COMPENSATE, keys, issueRequestId, memberId, epoch);
        } catch (DataAccessException | IllegalStateException e) {
            throw new CouponIssueStoreUnavailableException("영구 거절 보상에 Redis를 쓰지 못했다", e);
        }
        try {
            return IssueCompensation.fromLua(code);
        } catch (CouponDomainException e) {
            throw new CouponIssueStoreUnavailableException(
                    "영구 거절 보상 결과가 오류다 couponId=" + couponId + ", issueRequestId=" + issueRequestId + ", result=" + code,
                    e);
        }
    }
}
