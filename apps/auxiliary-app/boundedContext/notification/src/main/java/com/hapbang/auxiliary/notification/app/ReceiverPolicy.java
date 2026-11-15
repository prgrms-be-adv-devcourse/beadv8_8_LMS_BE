package com.hapbang.auxiliary.notification.app;

import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 받는 사람에게 알림을 만들지 정하는 한 곳. 탈퇴(ND-018)·정지(ND-072) 회원과 관리자(ND-003)는 만들지 않는다.
 * 만들지 않아도 실패가 아니라 커밋한다(DLT 아님).
 */
@Component
public class ReceiverPolicy {

    public boolean canReceive(NotificationUser receiver) {
        return receiver.getStatus() == UserStatus.ACTIVE && receiver.getRole() != Role.ADMIN;
    }
}
