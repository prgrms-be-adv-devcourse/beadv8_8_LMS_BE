package com.hapbang.appearance.in;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.hapbang.appearance.app.AppearanceUserService;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 회원 모듈의 이벤트를 받아 출연 회원(복제 회원)을 만들고 갱신한다.
 * <p>
 * {@code @ApplicationModuleListener}: 회원 모듈의 트랜잭션이 커밋된 뒤, 새 트랜잭션에서 처리한다.
 * 처리에 실패하면 Modulith 이벤트 저장소에 남아 다시 처리된다.
 */
// TODO(member): 회원 정보 변경·역할 변경·탈퇴 이벤트가 추가되면 같은 방식으로 받는다.
@Component
@RequiredArgsConstructor
public class AppearanceUserEventListener {

    private final AppearanceUserService appearanceUserService;

    @ApplicationModuleListener
    public void handle(UserJoinedEvent event) {
        appearanceUserService.sync(event.user());
    }
}
