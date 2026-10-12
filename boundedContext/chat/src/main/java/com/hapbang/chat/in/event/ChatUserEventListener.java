package com.hapbang.chat.in.event;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatUserFacade;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트를 받아 채팅 회원(ChatUser)으로 복제한다.
 * <p>
 * {@link ApplicationModuleListener}: 회원 트랜잭션이 커밋된 뒤 별도 트랜잭션에서 비동기로 처리한다.
 * 처리하지 못한 이벤트는 Modulith 발행 기록에 남아 재시작 시 다시 처리된다.
 * 가입은 없을 때만 생성하고 변경은 생성·갱신하므로, 늦게 재처리된 가입 이벤트가 변경 내용을 덮어쓰지 않는다.
 */
// TODO(event): 변경 이벤트끼리 순서가 뒤바뀌면(실패한 변경 이벤트의 재처리 등) 옛 정보가 반영될 수 있다.
//  UserDto에 버전(또는 수정 시각)을 넣고 ReplicaUser가 더 새로운 이벤트만 반영하도록 팀에 제안한다(#56).
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatUserEventListener {

    private final ChatUserFacade chatUserFacade;

    @ApplicationModuleListener
    public void on(UserJoinedEvent event) {
        chatUserFacade.syncJoined(event.user());
        log.info("채팅 회원 가입 반영 userId={}", event.user().id());
    }

    @ApplicationModuleListener
    public void on(UserModifiedEvent event) {
        chatUserFacade.syncModified(event.user());
        log.info("채팅 회원 변경 반영 userId={}", event.user().id());
    }
}
