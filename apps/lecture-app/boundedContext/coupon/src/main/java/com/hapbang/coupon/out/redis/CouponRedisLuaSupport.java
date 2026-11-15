package com.hapbang.coupon.out.redis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 쿠폰 Lua 스크립트 읽기·실행. 값은 KEYS·ARGV로 넘기고 스크립트 문자열을 치환하지 않는다.
 * <p>
 * 스크립트는 클래스를 올릴 때 클래스패스의 {@code .lua} 파일을 문자열로 한 번 읽어 만든다. 파일 자원({@code ClassPathResource})으로
 * 만들면 Spring Data Redis가 호출마다 잡는 lock 안에서 파일 수정 시각을 확인한다(실행 jar 안의 파일이라 그때마다 jar 항목을 연다). 선착순
 * 발급처럼 같은 스크립트를 동시에 부르면 요청 스레드가 그 lock에서 줄을 선다. 문자열 스크립트도 lock은 잡지만 그 안의 파일 확인이 없어
 * 아주 짧다. 스크립트 내용·KEYS·ARGV는 그대로다.
 */
record CouponRedisLuaSupport() {

    static RedisScript<Long> longLuaScript(String path) {
        return RedisScript.of(read(path), Long.class);
    }

    /** 정수 배열(Lua table)을 돌려주는 스크립트 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static RedisScript<List<Long>> longListLuaScript(String path) {
        return (RedisScript) RedisScript.of(read(path), List.class);
    }

    /** 섞인 값의 배열(Lua table)을 돌려주는 스크립트 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static RedisScript<List<Object>> listLuaScript(String path) {
        return (RedisScript) RedisScript.of(read(path), List.class);
    }

    /** 클래스패스의 스크립트 파일을 UTF-8 문자열로 읽는다. 없으면 기동 때 바로 실패한다 */
    static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("쿠폰 Lua 스크립트를 읽지 못했다: " + path, e);
        }
    }

    static List<Long> runForLongs(StringRedisTemplate redisTemplate, RedisScript<List<Long>> script, List<String> keys,
            Object... args) {
        List<Long> result = redisTemplate.execute(script, keys, toStringArgs(args));
        if (result == null) {
            throw new IllegalStateException("Lua 결과 null");
        }
        return result;
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
