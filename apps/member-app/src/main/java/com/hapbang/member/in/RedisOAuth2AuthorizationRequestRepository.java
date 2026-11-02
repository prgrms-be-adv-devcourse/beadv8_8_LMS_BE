package com.hapbang.member.in;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 구글 로그인 진행 상태(state·PKCE·돌아올 주소)를 세션 대신 Redis에 둔다 (#370).
 * <ul>
 *     <li>로그인 시작: 묶음을 {@code member:oauth2-auth-request:{브라우저 ID}}에 3분 저장하고, 브라우저 ID만 쿠키로 준다.</li>
 *     <li>콜백: 쿠키의 ID로 꺼내면서 바로 지운다(한 번만 쓴다). 콜백의 state가 저장한 state와 다르면 없는 것으로 본다.</li>
 *     <li>쿠키로 "로그인을 시작한 브라우저"를 묶어야 다른 사람이 만든 콜백 링크로 로그인되는 것(로그인 CSRF)을 막는다.</li>
 *     <li>구글에서 돌아오는 이동에도 붙어야 해서 쿠키는 SameSite=Lax다. 쿠키에는 의미 없는 ID만 있고 PKCE 값은 서버에만 있다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RedisOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String COOKIE_NAME = "OAUTH2_AUTH_REQUEST";
    static final String KEY_PREFIX = "member:oauth2-auth-request:";
    static final Duration TTL = Duration.ofMinutes(3);
    /** 로그인 시작(/api/v1/members/oauth2/authorization)과 콜백(/api/v1/members/login/oauth2/code)을 모두 덮는 경로 */
    private static final String COOKIE_PATH = "/api/v1/members";
    private static final int ID_BYTES = 32;
    private static final Pattern ID_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
    /** Redis에서 읽는 값은 이 앱이 쓴 것뿐이지만, 역직렬화는 필요한 클래스로만 제한한다 */
    private static final ObjectInputFilter ALLOWED_CLASSES =
            ObjectInputFilter.Config.createFilter("java.**;org.springframework.security.**;!*");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String id = browserId(request);
        if (id == null) {
            return null;
        }
        return matchingState(deserialize(redisTemplate.opsForValue().get(KEY_PREFIX + id)), request);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
                                         HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        String id = newBrowserId();
        redisTemplate.opsForValue().set(KEY_PREFIX + id, serialize(authorizationRequest), TTL);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(id, TTL).toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        String id = browserId(request);
        if (id == null) {
            return null;
        }
        String stored = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + id);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
        return matchingState(deserialize(stored), request);
    }

    /** 콜백의 state가 저장한 state와 같을 때만 돌려준다 (Spring 기본 세션 저장소와 같은 규칙) */
    private static OAuth2AuthorizationRequest matchingState(OAuth2AuthorizationRequest stored,
                                                            HttpServletRequest request) {
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        return stored != null && state != null && state.equals(stored.getState()) ? stored : null;
    }

    private static String browserId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && ID_FORMAT.matcher(value).matches())
                .findFirst()
                .orElse(null);
    }

    private static String newBrowserId() {
        byte[] bytes = new byte[ID_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private static String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(authorizationRequest);
        } catch (IOException e) {
            throw new IllegalStateException("로그인 진행 상태를 저장하지 못했습니다.", e);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static OAuth2AuthorizationRequest deserialize(String stored) {
        if (stored == null) {
            return null;
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(stored)))) {
            in.setObjectInputFilter(ALLOWED_CLASSES);
            return (OAuth2AuthorizationRequest) in.readObject();
        } catch (IOException | ClassNotFoundException | ClassCastException | IllegalArgumentException e) {
            // 배포로 클래스가 바뀌었거나 값이 깨졌으면 진행 중이던 로그인만 실패한다 (다시 시작하면 된다)
            return null;
        }
    }
}
