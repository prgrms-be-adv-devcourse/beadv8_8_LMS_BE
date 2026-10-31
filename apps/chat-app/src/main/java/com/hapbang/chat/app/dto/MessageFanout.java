package com.hapbang.chat.app.dto;

import java.util.UUID;

import com.hapbang.chat.domain.ChatNotifyRule;

/**
 * 방 채널({@code chat:room:{roomId}:v1})의 payload. 수신자 목록을 싣지 않고, 메시지가 저장 때 가리킨 불변 수신자 집합의 버전만 싣는다.
 * 계약: {@code apps/chat-app/docs/contracts/realtime-fanout.md}
 *
 * @param deliveryId            outbox의 안정 ID. 재시도·재적재해도 같다
 * @param recipientSetVersion   메시지가 가리킨 수신자 집합의 방 안 버전. 참여가 바뀔 때마다 1씩 는다
 * @param message               전송 ID 없는 공용 메시지 응답
 * @param notifyRule            새 메시지 알림을 보낼지
 * @param senderClientMessageId 보낸 사람 세션의 프레임에만 붙일 전송 ID. 없으면 null
 */
public record MessageFanout(
        UUID deliveryId,
        Type type,
        Long roomId,
        int recipientSetVersion,
        ChatMessageResponse message,
        ChatNotifyRule notifyRule,
        String senderClientMessageId
) {

    public enum Type {
        MESSAGE
    }
}
