package com.hapbang.chat.domain;

/**
 * 단체방이 읽기 전용({@link ChatRoomStatus#READ_ONLY})이 된 사유. 사용 중인 방과 1:1 방은 null이다.
 * 호스트의 정지·탈퇴 같은 세부 사유는 두지 않고 자격 상실 한 종류로 둔다.
 */
public enum ChatRoomStatusReason {
    /** 호스트가 직접 폐쇄했다. 호스트도 개별로 나갈 수 있다 */
    CLOSED_BY_HOST,
    /** 호스트가 방을 운영할 수 없게 됐다(탈퇴·호스트 자격이 없는 역할). 회원 변경 반영이 바꾼다. 자격을 회복해도 되돌리지 않는다 */
    HOST_UNAVAILABLE
}
