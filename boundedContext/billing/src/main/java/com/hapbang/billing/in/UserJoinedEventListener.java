package com.hapbang.billing.in;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import com.hapbang.billing.app.WalletFacade;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;

// 회원 가입 이벤트를 받아 관리자를 제외한 회원의 지갑을 만든다.
@Component
@RequiredArgsConstructor
public class UserJoinedEventListener {

    private final WalletFacade walletFacade;

    // 가입 커밋 후 트랜잭션 없이 비동기로 실행하며, 실패하면 발행 기록이 미완료로 남아 재시작 시 다시 처리된다.
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(UserJoinedEvent event) {
        UserDto user = event.user();
        // 관리자는 HP를 쓰지 않으므로 지갑을 만들지 않는다.
        if (user.role() == Role.ADMIN) {
            return;
        }
        // 재전달되었거나 지갑 조회가 먼저 만들었으면 기존 지갑을 그대로 쓴다 (회원당 지갑 1개).
        walletFacade.getOrCreateWallet(user.id());
    }
}
