package com.hapbang.auxiliary.notification.app;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.domain.NotificationUser;

/**
 * 받는 사람에게 알림을 만들지 정하는 한 곳(ND-018). 탈퇴한 회원은 만들지 않는다.
 * 정지 회원 처리는 미결(U8, 팀 결정)이다. 알림을 만드는 첫 소비자(N5) 전에 정하고 이 메서드만 고친다(ND-050).
 */
@Component
public class ReceiverPolicy {

    public boolean canReceive(NotificationUser receiver) {
        return !receiver.isWithdrawn();
    }
}
