package com.hapbang.member.out;

/**
 * 비밀번호 확인 실패 횟수와 잠금 (#499). 같은 키로 정해진 횟수만큼 연속으로 틀리면 일정 시간 막는다.
 * 지금은 비밀번호 변경이 {@code password-change:{회원 ID}} 키로 쓴다. 비밀번호 로그인은 (이메일, IP)마다 세는
 * {@link PasswordLoginLockStore}로 옮겼다(#506)
 */
public interface LoginAttemptStore {

    /** 이 키가 지금 잠겨 있는지 */
    boolean isLocked(String key);

    /** 실패를 하나 센다. 정해진 횟수에 이르면 잠그고 true를 돌려준다 */
    boolean recordFailure(String key);

    /** 로그인에 성공하면 실패 횟수를 지운다 */
    void reset(String key);

    /** 실패 횟수와 잠금을 모두 지운다 (비밀번호를 재설정하면 잠금도 푼다, #557) */
    void unlock(String key);
}
