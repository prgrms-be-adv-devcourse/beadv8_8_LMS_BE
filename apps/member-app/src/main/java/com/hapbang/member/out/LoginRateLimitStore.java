package com.hapbang.member.out;

/** 로그인 요청을 IP마다 센다 (#506). 키는 {@code ClientIpKey}의 세는 단위다 */
public interface LoginRateLimitStore {

    /**
     * 요청 하나를 센다.
     *
     * @return 받았으면 0, 한도를 넘었으면 다시 받을 수 있을 때까지 남은 밀리초(1 이상)
     */
    long tryAcquire(String ipKey);
}
