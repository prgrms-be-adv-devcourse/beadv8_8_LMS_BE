package com.hapbang.chat.in.event;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import com.hapbang.chat.app.ChatNotificationFacade;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatNotificationResult;
import com.hapbang.chat.in.websocket.ChatMessageSender;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;

import lombok.RequiredArgsConstructor;

/**
 * 출연 이벤트를 받아 채팅 알림 메시지로 남기고 STOMP로 보낸다 (#229).
 * <p>
 * 출연 트랜잭션이 커밋된 뒤 비동기로 실행된다. 리스너 자체는 트랜잭션을 열지 않는다({@code NOT_SUPPORTED}).
 * 그래서 Facade의 재시도가 새 트랜잭션에서 돌고, 알림이 커밋된 뒤에 STOMP로 보낸다.
 * 처리하지 못한 이벤트는 Modulith 발행 기록에 남아 재시작 시 다시 처리되며, 같은 알림은 한 번만 남는다.
 */
@Component
@RequiredArgsConstructor
public class AppearanceNotificationEventListener {

    private final ChatNotificationFacade chatNotificationFacade;
    private final ChatMessageSender chatMessageSender;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(AppearanceRequestSentEvent event) {
        chatNotificationFacade.notifyAppearanceRequestSent(event.request()).ifPresent(this::deliver);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(AppearanceApplicationSubmittedEvent event) {
        chatNotificationFacade.notifyAppearanceApplicationSubmitted(event.application()).ifPresent(this::deliver);
    }

    private void deliver(ChatNotificationResult result) {
        for (ChatMessageResponse rejoinNotice : result.rejoinNotices()) {
            chatMessageSender.deliver(rejoinNotice, result.remainingUserIds());
        }
        chatMessageSender.deliver(result.sent().message(), result.sent().deliverToUserIds());
        chatMessageSender.notifyNewMessage(result.sent().message(), result.sent().notifyUserIds());
    }
}
