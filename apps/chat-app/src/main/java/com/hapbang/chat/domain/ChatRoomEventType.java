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
    ROOM_CLOSED,
    /**
     * 읽기 전용 단체방의 참여자 조회 기간이 끝났다(조회 만료 배치가 남긴다). 시스템 전환이지만 행위자 칸을 비울 수 없어 방의 호스트를 남긴다.
     * 일어난 시각은 배치 실행 시각이 아니라 조회 만료 시각이다
     */
    HISTORY_EXPIRED
}
