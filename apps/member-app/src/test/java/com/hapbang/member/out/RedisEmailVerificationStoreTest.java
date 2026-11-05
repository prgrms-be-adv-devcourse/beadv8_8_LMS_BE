package com.hapbang.member.out;

import static com.hapbang.member.domain.EmailVerificationPurpose.PASSWORD_RESET;
import static com.hapbang.member.domain.EmailVerificationPurpose.SIGN_UP;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.EmailVerificationPolicy;
import com.hapbang.member.domain.EmailVerificationPurpose;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/** 가입 이메일 인증 저장소 (#557): 실제 Redis로 재발송 간격·IP 횟수·코드 저장을 확인한다 */
@MemberIntegrationTest
@Import(RedisTestcontainersConfig.class)
class RedisEmailVerificationStoreTest {

    @Autowired
    RedisEmailVerificationStore store;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("재발송 간격 안에는 다시 시작할 수 없고(60초), 지우면 바로 다시 시작할 수 있다")
    void resendCooldown() {
        String email = newEmail();

        assertThat(store.tryStartResendCooldown(email)).isTrue();
        assertThat(store.tryStartResendCooldown(email)).isFalse();
        assertThat(redisTemplate.getExpire(RedisEmailVerificationStore.COOLDOWN_KEY_PREFIX + RedisLoginAttemptStore.hash(email),
                TimeUnit.SECONDS)).isBetween(1L, 60L);

        store.clearResendCooldown(email);
        assertThat(store.tryStartResendCooldown(email)).isTrue();
    }

    @Test
    @DisplayName("한 IP는 10통까지 세고 11통째부터 막는다. 세는 기간은 1시간이다")
    void ipLimit() {
        String ip = newIp();
        for (int i = 0; i < EmailVerificationPolicy.IP_LIMIT; i++) {
            assertThat(store.tryCountIp(ip)).isTrue();
        }

        assertThat(store.tryCountIp(ip)).isFalse();
        assertThat(store.tryCountIp(newIp())).isTrue();
        assertThat(redisTemplate.getExpire(RedisEmailVerificationStore.IP_KEY_PREFIX + RedisLoginAttemptStore.hash(ip),
                TimeUnit.MINUTES)).isBetween(59L, 60L);
    }

    @Test
    @DisplayName("코드는 10분 동안 저장하고, 다시 저장하면 새 코드로 바뀌며 틀린 횟수는 지운다. 키에 이메일 원문은 없다")
    void saveCode() {
        String email = newEmail();
        String hash = RedisLoginAttemptStore.hash(email);
        redisTemplate.opsForValue().set(RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.FAIL_KEY_PREFIX, SIGN_UP, hash), "3");

        store.saveCode(email, SIGN_UP, "111111");
        store.saveCode(email, SIGN_UP, "222222");

        assertThat(redisTemplate.opsForValue().get(RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.CODE_KEY_PREFIX, SIGN_UP, hash))).isEqualTo("222222");
        assertThat(redisTemplate.hasKey(RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.FAIL_KEY_PREFIX, SIGN_UP, hash))).isFalse();
        assertThat(redisTemplate.getExpire(RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.CODE_KEY_PREFIX, SIGN_UP, hash), TimeUnit.MINUTES))
                .isBetween(9L, 10L);
        assertThat(hash).doesNotContain("@");

        store.deleteCode(email, SIGN_UP);
        assertThat(redisTemplate.hasKey(RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.CODE_KEY_PREFIX, SIGN_UP, hash))).isFalse();
    }

    @Test
    @DisplayName("맞는 코드는 MATCHED이고 코드를 지워 다시 쓸 수 없다")
    void checkCodeMatched() {
        String email = newEmail();
        store.saveCode(email, SIGN_UP, "123456");

        assertThat(store.checkCode(email, SIGN_UP, "123456", 5)).isEqualTo(EmailVerificationStore.CodeCheck.MATCHED);
        assertThat(store.checkCode(email, SIGN_UP, "123456", 5)).isEqualTo(EmailVerificationStore.CodeCheck.NOT_FOUND);
    }

    @Test
    @DisplayName("4번 틀릴 때까지는 WRONG이고, 5번째에 ATTEMPTS_EXCEEDED로 코드를 지운다. 맞는 코드도 그 뒤엔 못 쓴다")
    void checkCodeAttempts() {
        String email = newEmail();
        store.saveCode(email, SIGN_UP, "123456");
        for (int i = 0; i < 4; i++) {
            assertThat(store.checkCode(email, SIGN_UP, "000000", 5)).isEqualTo(EmailVerificationStore.CodeCheck.WRONG);
        }
        assertThat(redisTemplate.getExpire(RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.FAIL_KEY_PREFIX, SIGN_UP, RedisLoginAttemptStore.hash(email)),
                TimeUnit.MINUTES)).isBetween(9L, 10L);

        assertThat(store.checkCode(email, SIGN_UP, "000000", 5)).isEqualTo(EmailVerificationStore.CodeCheck.ATTEMPTS_EXCEEDED);
        assertThat(store.checkCode(email, SIGN_UP, "123456", 5)).isEqualTo(EmailVerificationStore.CodeCheck.NOT_FOUND);
    }

    @Test
    @DisplayName("인증 토큰은 30분 동안 이메일과 묶이고, 지우면 사라진다. 저장 키에 토큰 원문은 없다")
    void token() {
        String email = newEmail();

        String token = store.issueToken(email, SIGN_UP);
        String key = RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.TOKEN_KEY_PREFIX, SIGN_UP, RedisLoginAttemptStore.hash(token));
        createdKeys.add(key);

        assertThat(token).hasSizeGreaterThanOrEqualTo(40);
        assertThat(key).doesNotContain(token);
        assertThat(redisTemplate.getExpire(key, TimeUnit.MINUTES)).isBetween(29L, 30L);
        assertThat(store.findTokenEmail(token, SIGN_UP)).contains(email);
        assertThat(store.findTokenEmail(token, SIGN_UP)).contains(email);
        store.deleteToken(token, SIGN_UP);
        assertThat(store.findTokenEmail(token, SIGN_UP)).isEmpty();
        assertThat(store.findTokenEmail("", SIGN_UP)).isEmpty();
        assertThat(store.findTokenEmail(null, SIGN_UP)).isEmpty();
    }

    @Test
    @DisplayName("한 이메일은 하루 5통까지 세고 6통째부터 막는다. 세는 기간은 하루다")
    void emailDailyLimit() {
        String email = newEmail();
        for (int i = 0; i < EmailVerificationPolicy.EMAIL_DAILY_LIMIT; i++) {
            assertThat(store.tryCountEmailDaily(email)).isTrue();
        }

        assertThat(store.tryCountEmailDaily(email)).isFalse();
        assertThat(store.tryCountEmailDaily(newEmail())).isTrue();
        assertThat(redisTemplate.getExpire(RedisEmailVerificationStore.EMAIL_DAILY_KEY_PREFIX + RedisLoginAttemptStore.hash(email),
                TimeUnit.HOURS)).isBetween(23L, 24L);
    }

    @Test
    @DisplayName("앱 전체 발송은 하루 300통까지 센다")
    void globalDailyLimit() {
        String key = RedisEmailVerificationStore.GLOBAL_DAILY_KEY;
        createdKeys.add(key);
        redisTemplate.opsForValue().set(key, String.valueOf(EmailVerificationPolicy.GLOBAL_DAILY_LIMIT - 1));

        assertThat(store.tryCountGlobalDaily()).isTrue();
        assertThat(store.tryCountGlobalDaily()).isFalse();
    }

    @Test
    @DisplayName("코드와 토큰은 용도마다 따로다: 가입 코드로 재설정 확인이 안 되고, 가입 토큰은 재설정 토큰으로 찾을 수 없다")
    void purposesAreSeparate() {
        String email = newEmail();
        store.saveCode(email, SIGN_UP, "123456");

        assertThat(store.checkCode(email, PASSWORD_RESET, "123456", 5)).isEqualTo(EmailVerificationStore.CodeCheck.NOT_FOUND);
        assertThat(store.checkCode(email, SIGN_UP, "123456", 5)).isEqualTo(EmailVerificationStore.CodeCheck.MATCHED);

        String token = store.issueToken(email, SIGN_UP);
        createdKeys.add(RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.TOKEN_KEY_PREFIX, SIGN_UP,
                RedisLoginAttemptStore.hash(token)));
        assertThat(store.findTokenEmail(token, PASSWORD_RESET)).isEmpty();
        assertThat(store.findTokenEmail(token, SIGN_UP)).contains(email);
    }

    private String newEmail() {
        String email = UUID.randomUUID() + "@example.com";
        String hash = RedisLoginAttemptStore.hash(email);
        createdKeys.add(RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.CODE_KEY_PREFIX, SIGN_UP, hash));
        createdKeys.add(RedisEmailVerificationStore.scoped(RedisEmailVerificationStore.FAIL_KEY_PREFIX, SIGN_UP, hash));
        createdKeys.add(RedisEmailVerificationStore.COOLDOWN_KEY_PREFIX + hash);
        createdKeys.add(RedisEmailVerificationStore.EMAIL_DAILY_KEY_PREFIX + hash);
        return email;
    }

    private String newIp() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        String ip = "2001:db8:%s:%s::1".formatted(hex.substring(0, 4), hex.substring(4, 8));
        createdKeys.add(RedisEmailVerificationStore.IP_KEY_PREFIX + RedisLoginAttemptStore.hash(ip));
        return ip;
    }
}
