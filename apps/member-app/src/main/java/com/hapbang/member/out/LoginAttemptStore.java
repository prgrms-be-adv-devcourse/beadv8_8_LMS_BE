package com.hapbang.member.out;

/**
 * 비밀번호 로그인 실패 횟수와 잠금 (#499). 같은 이메일로 정해진 횟수만큼 연속으로 틀리면 일정 시간 로그인을 막는다.
 * 이메일은 정규화한 값({@code MemberEmail.normalize})을 받는다
 */
public interface LoginAttemptStore {

    /** 이 이메일이 지금 잠겨 있는지 */
    boolean isLocked(String email);

    /** 실패를 하나 센다. 정해진 횟수에 이르면 잠그고 true를 돌려준다 */
    boolean recordFailure(String email);

    /** 로그인에 성공하면 실패 횟수를 지운다 */
    void reset(String email);
}
