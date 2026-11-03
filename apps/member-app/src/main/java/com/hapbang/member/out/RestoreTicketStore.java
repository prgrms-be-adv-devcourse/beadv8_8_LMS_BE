package com.hapbang.member.out;

import java.time.Duration;
import java.util.Optional;

/**
 * 탈퇴 회원 복구 티켓 (#593). 로그인으로 본인을 확인한 탈퇴 회원에게 주고, 복구 API가 이 티켓으로 본인을 확인한다.
 * 탈퇴 회원은 로그인 토큰을 받을 수 없어서 쓴다. 짧게 살고, 복구에 성공하면 지운다(실패하면 남겨 그대로 다시 시도할 수 있다).
 * 티켓 하나로 복구와 로그인이 되므로 화면 주소(쿼리)에 싣지 않는다 (#633)
 */
public interface RestoreTicketStore {

    /** 티켓 수명. 복구할까요? 화면에서 고르고 실패하면 다시 누를 시간이다. 복구 티켓 쿠키 수명도 같다 */
    Duration TTL = Duration.ofMinutes(10);

    /** 회원의 복구 티켓을 새로 만든다 */
    String issue(Long userId);

    /** 티켓의 회원 ID. 없거나 만료됐으면 빈 값 */
    Optional<Long> find(String ticket);

    /** 티켓을 지운다 (복구에 성공했을 때) */
    void delete(String ticket);
}
