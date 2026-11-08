package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * 모집글의 단체방 중 조회한 회원이 볼 수 있는 방(참여 중이고 조회 만료 전). 현재 방과 이전 방을 나눈다(Q37).
 *
 * @param currentRoom   현재 방(사용 중·일시 중지). 없거나 참여 중이 아니면 null
 * @param previousRooms 읽기 전용으로 남은 이전 방(최근 방부터). 나간 방·조회 만료된 방은 빠진다
 */
public record ChatGroupRoomsResponse(Long recruitId, ChatRoomResponse currentRoom,
        List<ChatRoomResponse> previousRooms) {
}
