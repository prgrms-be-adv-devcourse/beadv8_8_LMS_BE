package com.hapbang.member.domain;

import java.time.LocalDateTime;

/**
 * 탈퇴했지만 아직 복구할 수 있는 회원이 로그인했다 (#593). 409 {@code WITHDRAWN_RESTORABLE}에 복구 티켓과 복구 기한을 싣는다.
 * 프론트는 "복구할까요?"를 보여 주고, 복구하면 이 티켓으로 복구 API를 부른다
 */
public class WithdrawnRestorableException extends MemberException {

    private final String restoreTicket;
    private final LocalDateTime restorableUntil;

    public WithdrawnRestorableException(String restoreTicket, LocalDateTime restorableUntil) {
        super(MemberErrorCode.WITHDRAWN_RESTORABLE);
        this.restoreTicket = restoreTicket;
        this.restorableUntil = restorableUntil;
        addProperty("restoreTicket", restoreTicket);
        addProperty("restorableUntil", restorableUntil);
    }

    public String getRestoreTicket() {
        return restoreTicket;
    }

    public LocalDateTime getRestorableUntil() {
        return restorableUntil;
    }
}
