package com.hapbang.member.in;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 회원 정보가 바뀌면 커밋 뒤 그 회원의 세션 역할도 맞춘다 (같은 요청 스레드, 응답 전) */
@Slf4j
@Component
@RequiredArgsConstructor
class MemberSessionRoleListener {

    private final MemberSessionRoleUpdater memberSessionRoleUpdater;

    /** 실패해도 변경은 이미 커밋됐으므로 로그만 남긴다 (그 회원은 다시 로그인하면 반영) */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void updateSessionRole(UserModifiedEvent event) {
        try {
            memberSessionRoleUpdater.update(event.user().id(), event.user().role());
        } catch (RuntimeException e) {
            log.warn("세션 역할 갱신 실패 userId={}, role={}", event.user().id(), event.user().role(), e);
        }
    }
}
