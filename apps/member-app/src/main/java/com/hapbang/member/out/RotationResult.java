package com.hapbang.member.out;

/** refresh token 회전 결과 ({@link RefreshTokenStore#rotate}) */
public sealed interface RotationResult {

    /** 정상 회전: 새 토큰이 저장됐다. 이 회원에게 access token을 발급한다 */
    record Rotated(Long userId) implements RotationResult {
    }

    /**
     * 유예 시간 안에 이미 회전된 토큰이 다시 왔다 (여러 탭 동시 재발급). 새 토큰은 저장하지 않았다:
     * access token만 발급하고 refresh token 쿠키는 그대로 둔다 (먼저 끝난 요청이 준 새 쿠키를 브라우저가 쓴다)
     */
    record Grace(Long userId) implements RotationResult {
    }

    /** 유예 시간이 지난 뒤 이미 회전된 토큰이 다시 왔다: 탈취로 보고 이 회원의 토큰을 모두 폐기한다 */
    record Reused(Long userId) implements RotationResult {
    }

    /** 없거나 만료되거나 폐기된 토큰. 유예 안이라도 회전으로 받은 새 토큰이 폐기됐거나 이미 회전됐으면 이것이다 */
    record NotFound() implements RotationResult {
    }
}
