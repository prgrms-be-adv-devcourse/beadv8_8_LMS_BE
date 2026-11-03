package com.hapbang.chat.domain;

/**
 * 채팅방 기록의 종류. 행위자는 {@code actorUserId}, 행위 대상이 따로 있으면 {@code targetUserId}에 남긴다.
 */
public enum ChatRoomEventType {
    ROOM_CREATED,
    MEMBER_JOINED,
    MEMBER_LEFT,
    MEMBER_REJOINED,
    ROOM_ENDED,
    /** 호스트가 단체방에 초대했다(재초대 포함). 행위자는 호스트, 대상은 초대된 회원 */
    MEMBER_INVITED,
    /** 호스트가 단체방에서 내보냈다. 행위자는 호스트, 대상은 내보낸 회원 */
    MEMBER_KICKED,
    /** 호스트가 단체방을 폐쇄해 읽기 전용이 됐다. 행위자는 호스트 */
    ROOM_CLOSED
}
