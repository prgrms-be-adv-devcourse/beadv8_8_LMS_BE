package com.hapbang.chat.in.event;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatUserFacade;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 이벤트를 받아 채팅 회원(ChatUser)으로 복제한다.
 * <p>
 * {@link ApplicationModuleListener}: 회원 트랜잭션이 커밋된 뒤 별도 트랜잭션에서 비동기로 처리한다.
 * 처리하지 못한 이벤트는 Modulith 발행 기록에 남아 재시작 시 다시 처리된다. 복제는 upsert라 중복 수신에도 결과가 같다.
 */
// TODO(event): 역할 변경(채널 인증·승급), 닉네임 변경, 탈퇴 이벤트가 shared에 생기면 같은 방식으로 리스너를 추가한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatUserEventListener {

    private final ChatUserFacade chatUserFacade;

    @ApplicationModuleListener
    public void on(UserJoinedEvent event) {
        chatUserFacade.sync(event.user());
        log.info("채팅 회원 복제 userId={}", event.user().id());
    }
}
