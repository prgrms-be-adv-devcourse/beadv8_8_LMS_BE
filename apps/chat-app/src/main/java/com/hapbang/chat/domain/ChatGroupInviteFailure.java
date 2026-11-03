package com.hapbang.chat.domain;

/** 단체방 초대가 실패한 사유. 실패한 회원은 참여하지 않고 초대 후보 검색에 다시 나온다 */
public enum ChatGroupInviteFailure {
    /** 모집글에 지원 이력이 없다 */
    NOT_APPLICANT,
    /** 지금 채팅할 수 없는 회원이다(역할·탈퇴·정지, 채팅 회원 정보 없음) */
    CHAT_NOT_ALLOWED,
    /** 게스트 정원이 찼다. 요청 순서대로 빈자리를 채운 뒤의 회원이다 */
    ROOM_FULL
}
