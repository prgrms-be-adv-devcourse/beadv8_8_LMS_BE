package com.hapbang.member.in;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.RedisEmailVerificationStore;
import com.hapbang.member.out.UserRepository;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/** 가입 이메일 인증 API (#557): 로그인 없이 부르고, 메일 계정이 없는 테스트에서는 로그 발송기로 보낸다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
@Import(RedisTestcontainersConfig.class)
class EmailVerificationApiTest {

    private static final String EMAIL_CODES = "/api/v1/members/email-codes";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 */
    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    @Test
    @DisplayName("코드를 받으면 200과 유효 시간·재발송 대기 시간을 주고, 1분 안에 다시 받으면 429")
    void sendCode() throws Exception {
        String email = newEmail();
        String ip = newIp();

        sendCode(email, ip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600))
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60));
        sendCode(email, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EMAIL_CODE_RESEND_TOO_SOON"));
    }

    @Test
    @DisplayName("한 IP에서 10통을 넘게 받으면 429 EMAIL_CODE_TOO_MANY_REQUESTS")
    void ipLimit() throws Exception {
        String ip = newIp();
        for (int i = 0; i < RedisEmailVerificationStore.IP_LIMIT; i++) {
            sendCode(newEmail(), ip).andExpect(status().isOk());
        }

        sendCode(newEmail(), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EMAIL_CODE_TOO_MANY_REQUESTS"));
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 409 DUPLICATE_EMAIL, 이메일 형식이 틀리면 400")
    void rejects() throws Exception {
        String email = newEmail();
        userRepository.save(User.join(email, "tester" + UUID.randomUUID().toString().substring(0, 4)));

        sendCode(email.toUpperCase(), newIp())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        sendCode("not-an-email", newIp()).andExpect(status().isBadRequest());
    }

    private ResultActions sendCode(String email, String ip) throws Exception {
        return mockMvc.perform(post(EMAIL_CODES)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));
    }

    private String newEmail() {
        String email = UUID.randomUUID() + "@example.com";
        String hash = hash(email);
        createdKeys.add("member:email-code:" + hash);
        createdKeys.add("member:email-code-fail:" + hash);
        createdKeys.add("member:email-code-cooldown:" + hash);
        return email;
    }

    /** 테스트마다 겹치지 않는 공인 주소 (IPv6 문서용 대역 2001:db8::/32는 사설·루프백이 아니라 공인 주소로 본다) */
    private String newIp() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        String ip = "2001:db8:%s:%s:%s:%s::1".formatted(hex.substring(0, 4), hex.substring(4, 8), hex.substring(8, 12),
                hex.substring(12, 16));
        createdKeys.add("member:email-code-ip:" + hash(ip));
        return ip;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
