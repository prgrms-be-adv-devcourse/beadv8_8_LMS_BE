package com.hapbang.chat.app;

import org.springframework.stereotype.Component;

import com.hapbang.chat.app.dto.ChatUserSyncResult;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트 리스너가 회원 복제 기능을 쓰는 진입점. in 계층은 UseCase를 직접 쓰지 않고 이 Facade를 통한다.
 * 회원 이벤트 리스너에는 로그인 사용자가 없으므로 {@code @PreAuthorize}를 붙이지 않는다.
 * <p>
 * 트랜잭션과 연결 제어 기록은 {@link ChatUserSyncService} 구현이 맡는다. 이 Facade는 Kafka 타입을 모르고, 처리 결과만 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class ChatUserFacade {

    private final ChatUserSyncService chatUserSyncService;
    private final ChatUserEligibility chatUserEligibility;

    public ChatUserSyncResult syncJoined(UserDto user) {
        return chatUserSyncService.syncJoined(user);
    }

    /**
     * 회원 변경을 반영한다. 반영 결과가 채팅 불가이면 같은 트랜잭션에 연결 재검사 작업이 남는다.
     * 연결 종료는 그 작업이 제어 Stream·제어 채널을 거쳐 처리한다(리스너가 직접 닫지 않는다).
     */
    public ChatUserSyncResult syncModified(UserDto user) {
        return chatUserSyncService.syncModified(user);
    }

    /** 채팅할 수 있는 회원인지 (웹소켓 연결 검사용). 복제본이 없으면 false */
    public boolean canChat(Long userId) {
        return chatUserEligibility.canChat(userId);
    }
}
