package com.hapbang.chat.domain;

/**
 * 채팅방 상태. 1:1 방은 {@link #ACTIVE}·{@link #ENDED}만 쓰고, 모집용 단체방은 {@link #ACTIVE}·{@link #SUSPENDED}·
 * {@link #READ_ONLY}를 쓴다. 단체방은 모두 나가도 {@link #ENDED}가 되지 않는다.
 */
public enum ChatRoomStatus {
    /** 사용 중. 1:1 방은 참여자가 한 명 이상 남아 있는 방이다 */
    ACTIVE,
    /** 두 사람 모두 퇴장해 종료된 1:1 방. 다시 활성화하지 않는다. */
    ENDED,
    /**
     * 호스트 일시정지로 중단된 단체방. 조회·읽음·나가기만 허용하고 전송·초대·강퇴는 막는다. 현재 방으로 자리를 차지한다.
     * 회원 상태로 전환하는 기능은 아직 없다(상태 값만 둔다).
     */
    SUSPENDED,
    /**
     * 호스트 폐쇄 또는 호스트 자격 상실로 읽기 전용이 된 단체방. 남은 참여자만 자기 가시 범위를 조회 만료 시각 전까지 본다.
     * 현재 방이 아니므로 같은 모집글에 새 방을 만들 수 있다.
     */
    READ_ONLY,
    /**
     * 참여자 조회 기간이 끝난 단체방. 저장하지 않고 조회 때 {@code historyExpiresAt}으로 정한다(그 시각 포함). 관리자 기록 조회에만
     * 나온다.
     */
    EXPIRED
}
