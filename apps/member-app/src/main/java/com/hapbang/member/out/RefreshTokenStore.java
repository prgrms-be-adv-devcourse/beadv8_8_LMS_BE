package com.hapbang.member.out;

import java.time.Instant;

/**
 * refresh token 저장소 (#370). 토큰 원문이 아니라 해시만 받는다.
 * 구현을 바꿔도(Redis ↔ DB) 회전의 원자성은 구현이 책임진다: 같은 토큰으로 동시에 회전해도 결과가 꼬이지 않아야 한다
 */
public interface RefreshTokenStore {

    /** 로그인할 때 새 refresh token을 저장한다 */
    void save(Long userId, String tokenHash);

    /**
     * 옛 토큰을 쓰고 새 토큰을 저장하는 일을 한 번에 한다.
     * 이미 회전된 토큰이 유예 시간 안에 다시 오면 정상 회전으로, 지난 뒤에 오면 {@link RotationResult.Reused}로 답한다
     */
    RotationResult rotate(String oldTokenHash, String newTokenHash, Instant now);

    /** 토큰 하나를 폐기한다 (로그아웃) */
    void revoke(String tokenHash);

    /** 회원의 토큰을 모두 폐기한다 (재사용 감지, 정지·탈퇴) */
    void revokeAll(Long userId);
}
