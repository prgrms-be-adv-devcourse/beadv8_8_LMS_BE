package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatResyncReason;
import com.hapbang.chat.app.dto.ChatRoomActivity;
import com.hapbang.chat.app.dto.ChatSubscriptionReason;

/**
 * 수신 자격을 확인한 뒤 로컬 연결로 프레임을 보낸다. 자격 검사는 호출하는 쪽이 끝낸 상태여야 한다.
 * 보내기 실패는 예외로 알리지 않는다(전달 실패는 전송 실패가 아니다). 받지 못한 회원은 이력 조회로 복구한다.
 * 프레임 형식: {@code apps/chat-app/docs/contracts/resync-frame.md}
 */
public interface LocalChatSender {

    /**
     * {@code /user/queue/chats/messages}로 보낸 사람 회원에게 전송 결과 확인을 보낸다(같은 전송 ID 재전송의 응답 포함).
     *
     * @param participationVersion 보낸 사람의 참여 세대. 프레임 헤더 {@code participation-version}으로 싣는다
     */
    void sendMessage(Long userId, ChatMessageResponse message, int participationVersion);

    /**
     * 방 topic({@code /topic/chats/rooms/{roomId}}) 프레임을 구독마다 쓴다. payload는 한 번만 직렬화한다.
     * 프레임 헤더 {@code participation-version}은 그 구독의 세대다.
     */
    void sendRoomMessage(Long roomId, ChatMessageResponse message, List<RoomTarget> targets);

    /**
     * 방 topic 구독 하나에 거절·회수 프레임을 쓴다(헤더 {@code chat-frame-type}).
     *
     * @param currentParticipationVersion 세대 불일치일 때만 그 회원의 현재 세대. 아니면 null
     */
    void sendSubscriptionSignal(Long roomId, RoomTarget target, SubscriptionSignal signal,
            ChatSubscriptionReason reason, Integer currentParticipationVersion);

    /** {@code /user/queue/chats/notifications}로 새 메시지 알림을 그 연결 하나에만 보낸다 */
    void sendRoomActivity(Long userId, String sessionId, ChatRoomActivity activity);

    /** {@code /user/queue/chats/sync}로 재동기화 신호를 보낸다 */
    void sendResync(Long userId, ChatResyncReason reason, LocalDateTime occurredAt);

    /** 방 topic 프레임을 받을 구독 */
    record RoomTarget(String sessionId, String subscriptionId, int participationVersion) {
    }

    /** 방 topic 구독에 쓰는 메시지가 아닌 프레임 */
    enum SubscriptionSignal {
        SUBSCRIPTION_REJECTED,
        SUBSCRIPTION_REVOKED
    }
}
