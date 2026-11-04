package com.hapbang.chat.out.redis;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * classpath의 Lua 스크립트 파일({@code redis/scripts/})을 읽고 실행한다.
 * <ul>
 *     <li>읽기: 만들 때 파일을 한 번 읽는다. 상수로 두면 파일이 없거나 읽지 못할 때 빈 생성에서 앱 시작이 실패한다
 *     (첫 실행 때 늦게 실패하지 않는다).</li>
 *     <li>실행: 값은 스크립트에 끼워 넣지 않고 KEYS·ARGV로만 넘긴다. 스크립트가 늘 같아 SHA가 바뀌지 않으므로
 *     {@code EVALSHA} 캐시를 그대로 쓴다. ARGV는 {@link String#valueOf}로 바꿔 넘긴다.</li>
 * </ul>
 */
public record RedisLuaSupport() {

    public static RedisScript<Long> longLuaScript(String path) {
        return loaded(RedisScript.of(new ClassPathResource(path), Long.class));
    }

    public static long runForLong(StringRedisTemplate redisTemplate, RedisScript<Long> script,
                                  List<String> keys, Object... args) {
        Long result = redisTemplate.execute(script, keys, toStringArgs(args));
        if (result == null) {
            throw new IllegalStateException("Lua 결과 null");
        }
        return result;
    }

    /** {@link RedisScript#of}는 파일을 첫 실행 때 읽으므로, SHA를 미리 구해 지금 읽게 한다 */
    private static <T> RedisScript<T> loaded(RedisScript<T> script) {
        script.getSha1();
        return script;
    }

    private static Object[] toStringArgs(Object[] args) {
        return Arrays.stream(args).map(String::valueOf).toArray();
    }
}
