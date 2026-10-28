package com.hapbang.chat.in.event;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatUserFacade;
import com.hapbang.chat.in.websocket.ChatWebSocketSessionRegistry;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트를 받아 채팅 회원(ChatUser)으로 복제한다.
 * <p>
 * {@link ApplicationModuleListener}: 회원 트랜잭션이 커밋된 뒤 별도 트랜잭션에서 비동기로 처리한다.
 * 처리하지 못한 이벤트는 Modulith 발행 기록에 남아 재시작 시 다시 처리된다.
 * 채팅 회원은 가입 이벤트로만 생성한다. 가입보다 먼저 온 변경 이벤트는 실패로 남았다가, 재시작 시 다시 처리된다.
 */
// TODO(event): 변경 이벤트끼리 순서가 뒤바뀌면(실패한 변경 이벤트의 재처리 등) 옛 정보가 반영될 수 있다.
//  UserDto에 버전(또는 수정 시각)을 넣고 ReplicaUser가 더 새로운 이벤트만 반영하도록 팀에 제안한다(#56).
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatUserEventListener {

    private final ChatUserFacade chatUserFacade;
    private final ChatWebSocketSessionRegistry chatWebSocketSessionRegistry;

    @ApplicationModuleListener
    public void on(UserJoinedEvent event) {
        chatUserFacade.syncJoined(event.user());
        log.info("채팅 회원 가입 반영 userId={}", event.user().id());
    }

    /**
     * 이번 변경으로 채팅할 수 없게 되면(채팅 가능 → 불가: 정지·탈퇴·일반 유저로 강등) 그 회원의 열린 웹소켓 연결을 모두 닫는다(#242).
     * 회원 변경 이벤트는 프로필 수정에도 오므로, 채팅 가능 여부가 그대로인 변경(닉네임 수정, 인증된 인플루언서 승급 등)은
     * 연결을 유지한다.
     */
    @ApplicationModuleListener
    public void on(UserModifiedEvent event) {
        boolean lostChat = chatUserFacade.syncModified(event.user());
        log.info("채팅 회원 변경 반영 userId={}, lostChat={}", event.user().id(), lostChat);
        if (lostChat) {
            int closed = chatWebSocketSessionRegistry.closeByUserId(event.user().id(),
                    ChatWebSocketSessionRegistry.CHAT_NOT_ALLOWED);
            log.info("채팅할 수 없게 된 회원의 웹소켓 연결 닫음 userId={}, closed={}", event.user().id(), closed);
        }
    }
}
