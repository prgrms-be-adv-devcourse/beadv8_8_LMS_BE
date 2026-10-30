package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * 채팅 시작의 저장 결과. 방 응답(참여자·닉네임·마지막 메시지)은 저장 트랜잭션이 커밋된 뒤 따로 조립한다(설계 4.6).
 *
 * @param created       새로 만든 방이면 true, 기존 방을 재사용했으면 false
 * @param rejoinNotices 퇴장했던 회원을 재입장시켰으면 남아 있던 회원에게 보일 재입장 안내. 전달은 outbox가 맡는다
 */
public record ChatRoomOpenResult(Long chatRoomId, boolean created, List<ChatMessageResponse> rejoinNotices) {
}
