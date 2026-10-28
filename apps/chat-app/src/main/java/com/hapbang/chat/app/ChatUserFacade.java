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
    private final ChatValidationService chatValidationService;

    public void syncJoined(UserDto user) {
        chatUserUseCase.createIfAbsent(user);
    }

    /**
     * 회원 변경을 반영하고, 이번 변경으로 채팅할 수 없게 됐는지 돌려준다. true면 리스너가 열린 연결을 닫는다.
     * 프로필 수정처럼 채팅 가능 여부가 그대로인 변경은 false다.
     */
    public boolean syncModified(UserDto user) {
        return chatUserUseCase.update(user);
    }

    /** 채팅할 수 있는 회원인지 (웹소켓 연결 검사용). 복제본이 없으면 false */
    public boolean canChat(Long userId) {
        return chatValidationService.canChat(userId);
    }
}
