package com.hapbang.chat.app.dto;

/**
 * 서버 채널({@code chat:read:v1})의 payload. 방의 안 읽은 사람 수가 바뀌었을 수 있다는 신호다(읽음 위치·참여 변경 커밋 뒤).
 * 값은 싣지 않는다. 그 방을 연 로컬 구독이 있는 앱이 방별로 묶어(최대 1초) 다시 센 뒤 방 topic에 {@code READ_COUNTS}로 보낸다.
 * outbox를 거치지 않는 최선 노력이라 잃으면 FE가 REST 이력으로 보정한다.
 * 계약: {@code apps/chat-app/docs/contracts/unread-count.md}
 *
 * @param recipientSetVersion 참여 변경이면 그 변경이 만든 수신자 집합 버전, 읽음 위치 변경이면 0. 받는 앱이 방 참여 캐시를 다시 읽을지
 *                            정하는 기준이다(메시지 전달과 같은 버전 확인 규칙)
 */
public record ReadChangedFanout(Long roomId, int recipientSetVersion) {
}
