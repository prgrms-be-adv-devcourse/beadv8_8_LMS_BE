package com.hapbang.member.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.RefreshTokenStore;
import com.hapbang.member.out.RotationResult;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 토큰 발급·재발급·로그아웃 (#370). refresh token은 원문을 저장하지 않고 SHA-256 해시로만 저장소에 넘긴다.
 * 재발급은 토큰에 담긴 옛 역할이 아니라 회원 DB의 최신 역할·상태로 access token을 만든다 (승급이 다음 재발급에 반영된다)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberTokenUseCase {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenStore refreshTokenStore;
    private final UserRepository userRepository;

    /** 로그인: access token과 새 refresh token을 발급하고, refresh token은 해시로 저장한다 */
    public TokenPair issue(Long userId, Role role) {
        String refreshToken = newRefreshToken();
        refreshTokenStore.save(userId, hash(refreshToken));
        return new TokenPair(accessTokenIssuer.issue(userId, role), refreshToken);
    }

    /**
     * 재발급: refresh token을 새 토큰으로 바꾸고 access token을 발급한다.
     * 없거나 만료·폐기된 토큰, 재사용(탈취 의심), 이용할 수 없는 회원이면 모두 INVALID_REFRESH_TOKEN이다.
     * 재사용과 이용할 수 없는 회원은 그 회원의 refresh token을 모두 폐기한다
     */
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidRefreshToken();
        }
        String newRefreshToken = newRefreshToken();
        RotationResult result = refreshTokenStore.rotate(hash(refreshToken), hash(newRefreshToken), Instant.now());
        return switch (result) {
            case RotationResult.Rotated(Long userId) -> issueForActiveMember(userId, newRefreshToken);
            // 유예 안 재회전: 새 refresh token을 만들지 않았으므로 access token만 준다 (쿠키는 먼저 끝난 요청의 것을 쓴다)
            case RotationResult.Grace(Long userId) -> issueForActiveMember(userId, null);
            case RotationResult.Reused(Long userId) -> {
                log.warn("refresh token 재사용 감지: 회원의 refresh token을 모두 폐기한다 userId={}", userId);
                refreshTokenStore.revokeAll(userId);
                throw invalidRefreshToken();
            }
            case RotationResult.NotFound() -> throw invalidRefreshToken();
        };
    }

    /** 로그아웃: 이 기기의 refresh token만 폐기한다. 토큰이 없거나 이미 폐기됐어도 실패하지 않는다 */
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenStore.revoke(hash(refreshToken));
        }
    }

    private TokenPair issueForActiveMember(Long userId, String newRefreshToken) {
        User user = userRepository.findById(userId)
                .filter(found -> found.getStatus().isActive())
                .orElse(null);
        if (user == null) {
            log.info("이용할 수 없는 회원의 재발급 거부: 회원의 refresh token을 모두 폐기한다 userId={}", userId);
            refreshTokenStore.revokeAll(userId);
            throw invalidRefreshToken();
        }
        return new TokenPair(accessTokenIssuer.issue(user.getId(), user.getRole()), newRefreshToken);
    }

    /** 저장소에 넘길 해시. 저장소 내용이 새어도 원문을 알 수 없다 */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static MemberException invalidRefreshToken() {
        return new MemberException(MemberErrorCode.INVALID_REFRESH_TOKEN);
    }
}
