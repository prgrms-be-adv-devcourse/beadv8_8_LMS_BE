package com.hapbang.chat.app;

import org.springframework.stereotype.Component;

import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트 리스너가 회원 복제 기능을 쓰는 진입점. in 계층은 UseCase를 직접 쓰지 않고 이 Facade를 통한다.
 * 회원 이벤트 리스너에는 로그인 사용자가 없으므로 {@code @PreAuthorize}를 붙이지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatUserFacade {

    private final ChatUserUseCase chatUserUseCase;

    public void syncJoined(UserDto user) {
        chatUserUseCase.createIfAbsent(user);
    }

    public void syncModified(UserDto user) {
        chatUserUseCase.update(user);
    }
}
