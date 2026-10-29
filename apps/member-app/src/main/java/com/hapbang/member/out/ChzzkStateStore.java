package com.hapbang.member.out;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 치지직 연동 진행 상태를 Redis에 둔다 (#370). 콜백은 브라우저 이동이라 access token(Bearer)을 실을 수 없어서,
 * 연동을 시작할 때(로그인한 API 요청) 연동하는 회원과 state를 함께 맡겨 두고 콜백에서 브라우저 ID로 꺼낸다.
 * {@code member:chzzk-state:{브라우저 ID}} = "{회원 ID}:{state}", 10분. 꺼내면서 지운다(한 번만 쓴다)
 */
@Component
@RequiredArgsConstructor
public class ChzzkStateStore {

    static final String KEY_PREFIX = "member:chzzk-state:";
    /** 치지직 로그인·동의까지 넉넉히 */
    public static final Duration TTL = Duration.ofMinutes(10);
    private static final int ID_BYTES = 32;
    private static final Pattern ID_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    /** 연동하는 회원과 state를 맡기고, 브라우저에 줄 ID를 돌려준다 */
    public String save(Long userId, String state) {
        byte[] bytes = new byte[ID_BYTES];
        RANDOM.nextBytes(bytes);
        String browserId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(KEY_PREFIX + browserId, userId + ":" + state, TTL);
        return browserId;
    }

    /** 브라우저 ID로 꺼내면서 지운다. 형식이 틀리거나, 없거나, 만료됐으면 empty */
    public Optional<Entry> take(String browserId) {
        if (browserId == null || !ID_FORMAT.matcher(browserId).matches()) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + browserId);
        if (value == null) {
            return Optional.empty();
        }
        int separator = value.indexOf(':');
        return Optional.of(new Entry(Long.valueOf(value.substring(0, separator)), value.substring(separator + 1)));
    }

    /** 연동을 시작한 회원과 그때 만든 state */
    public record Entry(Long userId, String state) {
    }
}
