package com.hapbang.appearance.in;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.hapbang.appearance.app.AppearanceUserService;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 회원 모듈의 이벤트를 받아 출연 회원(복제 회원)을 만들고 갱신한다.
 * <p>
 * {@code @ApplicationModuleListener}: 회원 모듈의 트랜잭션이 커밋된 뒤, 새 트랜잭션에서 처리한다.
 * 처리에 실패하면 Modulith 이벤트 저장소에 남고, 서버가 재시작될 때 다시 처리된다.
 * <p>
 * 가입·변경 모두 이벤트에 담긴 회원 정보를 그대로 반영한다 (없으면 생성, 있으면 갱신).
 */
@Component
@RequiredArgsConstructor
public class AppearanceUserEventListener {

    private final AppearanceUserService appearanceUserService;

    @ApplicationModuleListener
    public void handle(UserJoinedEvent event) {
        appearanceUserService.sync(event.user());
    }

    /** 회원 정보(닉네임·역할·상태) 변경 */
    @ApplicationModuleListener
    public void handle(UserModifiedEvent event) {
        appearanceUserService.sync(event.user());
    }
}
