package com.hapbang.appearance.in;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.hapbang.appearance.app.AppearanceUserFacade;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 회원 모듈의 이벤트를 받아 출연 회원(복제 회원)을 만들고 갱신한다.
 * <p>
 * {@code @ApplicationModuleListener}: 회원 모듈의 트랜잭션이 커밋된 뒤, 새 트랜잭션에서 처리한다.
 * 처리에 실패하면 Modulith 이벤트 저장소에 남고, 서버가 재시작될 때 다시 처리된다.
 * <p>
 * 출연 회원은 가입 이벤트로만 만든다. 가입보다 먼저 온 변경 이벤트는 실패로 남았다가, 재시작 때 다시 처리된다.
 * 변경 이벤트의 순서가 뒤바뀌거나 중복돼도 공통 {@code ReplicaUser}가 회원 버전을 비교해 옛 정보는 무시한다 (회원 #292).
 */
@Component
@RequiredArgsConstructor
public class AppearanceUserEventListener {

    private final AppearanceUserFacade appearanceUserFacade;

    @ApplicationModuleListener
    public void handle(UserJoinedEvent event) {
        appearanceUserFacade.syncJoined(event.user());
    }

    /** 회원 정보(닉네임·역할·상태) 변경 */
    @ApplicationModuleListener
    public void handle(UserModifiedEvent event) {
        appearanceUserFacade.syncModified(event.user());
    }
}
