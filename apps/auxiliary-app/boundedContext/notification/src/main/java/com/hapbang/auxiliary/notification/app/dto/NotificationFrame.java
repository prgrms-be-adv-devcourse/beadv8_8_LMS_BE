package com.hapbang.auxiliary.notification.app.dto;

/**
 * 실시간 프레임 본문(ND-059). 저장된 알림 한 건과 그때의 안 읽은 수다. 프레임은 유실될 수 있으므로 FE는 재접속하면 REST 목록으로
 * 맞춘다(ND-027).
 *
 * @param notification 목록 항목과 같은 모양
 * @param unreadCount  보이는 최신 20개 안의 안 읽은 수
 */
public record NotificationFrame(NotificationItem notification, long unreadCount) {
}
