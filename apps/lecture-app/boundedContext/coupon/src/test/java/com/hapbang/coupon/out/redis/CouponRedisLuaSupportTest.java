package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.StaticScriptSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 쿠폰 Lua 스크립트를 파일 자원이 아니라 기동 때 읽은 문자열로 만드는지 본다. 파일 자원으로 만들면 Spring Data Redis가 호출마다 lock을 잡고
 * 파일 수정 시각을 확인한다. 스크립트가 Redis에서 그대로 도는지는 Lua·저장소 테스트(Testcontainers Redis)가 본다.
 */
class CouponRedisLuaSupportTest {

    @Test
    void 스크립트는_파일_내용을_문자열로_읽어_만들고_호출마다_파일을_다시_보지_않는다() throws IOException {
        String file = new ClassPathResource("lua/issue.lua").getContentAsString(StandardCharsets.UTF_8);

        RedisScript<?> script = CouponRedisLuaSupport.longListLuaScript("lua/issue.lua");

        assertThat(script.getScriptAsString()).isEqualTo(file);
        assertThat(ReflectionTestUtils.getField(script, "scriptSource")).isInstanceOf(StaticScriptSource.class);
        assertThat(script.getSha1()).isEqualTo(RedisScript.of(file).getSha1());
    }

    @Test
    void 없는_스크립트는_읽을_때_바로_실패한다() {
        assertThatThrownBy(() -> CouponRedisLuaSupport.longLuaScript("lua/no-such-script.lua"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("lua/no-such-script.lua");
    }
}
