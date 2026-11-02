package com.hapbang.chat.app.dto;

/**
 * 방을 열지 않은 참여자에게 보내는 새 메시지 알림. 방별로 묶은 메시지 수와 마지막 메시지를 담는다.
 *
 * @param participationVersion 받는 회원의 현재 참여 세대
 * @param lastSequence         묶음의 마지막 메시지 순번
 * @param messageCount         묶음 안에서 그 회원에게 보이는, 그 회원이 보내지 않은 메시지 수
 * @param lastMessage          묶음의 마지막 메시지(전송 ID 없는 공용 응답)
 */
public record ChatRoomActivity(
        Long chatRoomId,
        int participationVersion,
        long lastSequence,
        int messageCount,
        ChatMessageResponse lastMessage
) {
}
