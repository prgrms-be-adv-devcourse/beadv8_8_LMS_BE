package com.hapbang.chat.app;

import org.springframework.stereotype.Component;

import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트 리스너가 회원 복제 기능을 쓰는 진입점. in 계층은 Service를 직접 쓰지 않고 이 Facade를 통한다.
 */
@Component
@RequiredArgsConstructor
public class ChatUserFacade {

    private final ChatUserService chatUserService;

    public void sync(UserDto user) {
        chatUserService.sync(user);
    }
}
