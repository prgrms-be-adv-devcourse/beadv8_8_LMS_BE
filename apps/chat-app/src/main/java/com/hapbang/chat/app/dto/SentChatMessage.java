package com.hapbang.chat.app.dto;

/**
 * 메시지 전송 결과. 전달 대상은 담지 않는다. 새 메시지의 전달은 저장 트랜잭션에 함께 남긴 outbox가 맡는다.
 *
 * @param message 전송 ID 없는 메시지 응답
 * @param resent  같은 참여 안의 같은 전송 ID 재전송이라 새로 저장하지 않고 기존 메시지를 돌려줬으면 true.
 *                재전송은 전달 작업을 만들지 않으므로 호출한 쪽이 보낸 사람에게만 바로 다시 보낸다
 * @param senderParticipationVersion 보낸 사람의 현재 참여 세대. 재전송 응답 프레임의 {@code participation-version} 헤더로 싣는다
 */
public record SentChatMessage(ChatMessageResponse message, boolean resent, int senderParticipationVersion) {
}
