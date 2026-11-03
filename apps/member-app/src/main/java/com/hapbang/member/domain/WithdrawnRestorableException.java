package com.hapbang.member.domain;

import java.time.LocalDateTime;

/**
 * 탈퇴했지만 아직 복구할 수 있는 회원이 로그인했다 (#593). 409 {@code WITHDRAWN_RESTORABLE}에는 복구 기한만 싣는다.
 * 복구 티켓은 티켓 하나로 복구와 로그인이 되는 값이라 본문에 싣지 않고, 받은 컨트롤러가 복구 티켓 쿠키로 내려준다 (#633).
 * 프론트는 "복구할까요?"를 보여 주고, 복구하면 본문 없이 복구 API를 부른다(쿠키가 붙어 간다)
 */
public class WithdrawnRestorableException extends MemberException {

    private final String restoreTicket;
    private final LocalDateTime restorableUntil;

    public WithdrawnRestorableException(String restoreTicket, LocalDateTime restorableUntil) {
        super(MemberErrorCode.WITHDRAWN_RESTORABLE);
        this.restoreTicket = restoreTicket;
        this.restorableUntil = restorableUntil;
        addProperty("restorableUntil", restorableUntil);
    }

    public String getRestoreTicket() {
        return restoreTicket;
    }

    public LocalDateTime getRestorableUntil() {
        return restorableUntil;
    }
}
