package com.hapbang.chat.in.dto;

/**
 * STOMP 처리 중 발생한 오류. /user/queue/errors로 보낸 사람에게만 보낸다.
 */
public record ChatErrorMessage(String code, String message) {
}
