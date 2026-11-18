package com.hapbang.chat.app.dto;

/**
 * 채팅 시작의 저장 결과. 방 응답(참여자·닉네임·마지막 메시지)은 저장 트랜잭션이 커밋된 뒤 따로 조립한다(설계 4.6).
 * 재입장 안내의 전달은 같은 트랜잭션에 남긴 outbox가 맡는다.
 *
 * @param created 새로 만든 방이면 true, 기존 방을 재사용했으면 false
 */
public record ChatRoomOpenResult(Long chatRoomId, boolean created) {
}
