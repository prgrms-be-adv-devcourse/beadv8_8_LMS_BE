package com.hapbang.auxiliary.notification.app.dto;

/**
 * Redis Pub/Sub으로 서버 사이에 넘기는 실시간 전달 작업(ND-027). 받는 쪽 서버는 {@code receiverUserId}의 로컬 연결에만 {@code frame}을 보낸다.
 */
public record RealtimeMessage(Long receiverUserId, NotificationFrame frame) {
}
