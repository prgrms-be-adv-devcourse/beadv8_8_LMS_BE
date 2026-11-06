package com.hapbang.coupon.out.redis;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 쿠폰 Lua 스크립트 읽기·실행. 값은 KEYS·ARGV로 넘기고 스크립트 문자열을 치환하지 않는다 */
record CouponRedisLuaSupport() {

    static RedisScript<Long> longLuaScript(String path) {
        return RedisScript.of(new ClassPathResource(path), Long.class);
    }

    static long runForLong(StringRedisTemplate redisTemplate, RedisScript<Long> script, List<String> keys,
            Object... args) {
        Long result = redisTemplate.execute(script, keys, toStringArgs(args));
        if (result == null) {
            throw new IllegalStateException("Lua 결과 null");
        }
        return result;
    }

    private static Object[] toStringArgs(Object[] args) {
        return Arrays.stream(args).map(String::valueOf).toArray();
    }
}
