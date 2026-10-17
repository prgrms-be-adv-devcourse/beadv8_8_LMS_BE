package com.hapbang.chat.in.dto;

/**
 * STOMP 처리 중 발생한 오류. /user/queue/chats/errors로 보낸 사람에게만 보낸다.
 *
 * @param clientMessageId 실패한 전송에 붙어 있던 임시 ID. 보내지 않았거나 요청 본문을 읽지 못했으면 null
 */
public record ChatErrorMessage(String code, String message, String clientMessageId) {
}
