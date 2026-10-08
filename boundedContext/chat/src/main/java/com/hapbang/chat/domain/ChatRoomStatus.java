package com.hapbang.chat.domain;

public enum ChatRoomStatus {
    /** 참여자가 한 명 이상 남아 있는 방 */
    ACTIVE,
    /** 두 사람 모두 퇴장해 종료된 방. 다시 활성화하지 않는다. */
    ENDED
}
