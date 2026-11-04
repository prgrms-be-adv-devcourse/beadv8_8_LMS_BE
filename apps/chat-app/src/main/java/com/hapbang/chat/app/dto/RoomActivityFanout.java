package com.hapbang.chat.app.dto;

import java.util.UUID;

import com.hapbang.chat.domain.ChatNotifyRule;

/**
 * 서버 채널({@code chat:activity:v1})의 payload. 보낸 사람의 전송 결과 확인과 방을 열지 않은 참여자에게 보낼 새 메시지 알림의
 * 재료다. 모든 앱이 늘 구독하므로 보낸 사람이 그 방을 구독하지 않은 앱에서도 결과 확인을 보낼 수 있다.
 * 계약: {@code apps/chat-app/docs/contracts/realtime-fanout.md}
 *
 * @param lastMessage           전송 ID 없는 공용 메시지 응답
 * @param senderClientMessageId 보낸 사람의 전송 ID. 없으면 null(시스템 안내 등)
 */
public record RoomActivityFanout(
        UUID deliveryId,
        Long roomId,
        int recipientSetVersion,
        long roomSequence,
        ChatNotifyRule notifyRule,
        Long senderUserId,
        ChatMessageResponse lastMessage,
        String senderClientMessageId
) {

    /** 방 채널 payload에서 알림 재료를 만든다 */
    public static RoomActivityFanout from(MessageFanout fanout) {
        return new RoomActivityFanout(fanout.deliveryId(), fanout.roomId(), fanout.recipientSetVersion(),
                fanout.message().roomSequence(), fanout.notifyRule(), fanout.message().senderUserId(),
                fanout.message(), fanout.senderClientMessageId());
    }
}
