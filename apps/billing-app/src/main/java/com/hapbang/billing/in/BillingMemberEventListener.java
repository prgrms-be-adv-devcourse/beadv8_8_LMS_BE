package com.hapbang.billing.in;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import com.hapbang.billing.app.BillingUserSyncUseCase;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

// 회원 가입·변경 이벤트로 Billing 회원 복제본을 동기화한다 (#294). 지갑 생성(UserJoinedEventListener)과 별도 리스너라
// Modulith가 리스너마다 처리 결과를 따로 기록한다: 복제본 반영이 실패해도 지갑 생성은 영향을 받지 않고, 실패한 쪽만 재시작 때 다시 처리된다.
// 관리자도 복제한다(지갑과 달리). 가입 커밋 후 트랜잭션 없이 비동기로 실행하며 반영은 BillingUserSyncUseCase가 짧은 트랜잭션으로 한다.
@Component
@RequiredArgsConstructor
public class BillingMemberEventListener {

    private final BillingUserSyncUseCase billingUserSyncUseCase;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(UserJoinedEvent event) {
        billingUserSyncUseCase.apply(event.user());
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(UserModifiedEvent event) {
        billingUserSyncUseCase.apply(event.user());
    }
}
