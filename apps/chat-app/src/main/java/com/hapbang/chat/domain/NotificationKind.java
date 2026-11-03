package com.hapbang.chat.domain;

/**
 * 값으로 남기는 메시지의 종류. 서버는 문구를 만들지 않고 종류와 종류별 값({@link NotificationData})만 남기며, 프론트가 종류별 템플릿으로
 * 문구를 만든다.
 * <ul>
 *     <li>출연 알림({@link ChatMessageType#NOTIFICATION}): {@code referenceId}(알림 대상)와 {@code parentReferenceId}(알림 대상의
 *     상위 건)가 가리키는 출연 건의 종류도 정한다. 같은 출연 이벤트의 알림은 (종류, 대상 ID) 유일 인덱스로 하나만 남긴다.</li>
 *     <li>단체방 안내({@link ChatMessageType#SYSTEM}, {@code GROUP_}으로 시작): 행위자는 보낸 사람이고 값에 행위자·대상 회원 ID를
 *     담는다. {@code referenceId}는 null이라 출연 알림의 유일 인덱스에 걸리지 않는다(null은 서로 다른 값으로 본다).</li>
 * </ul>
 */
public enum NotificationKind {
    /** 출연 요청 도착 (게스트가 받는다). referenceId = 출연 요청서 ID, parentReferenceId = 없음 */
    APPEARANCE_REQUEST_SENT,
    /** 출연 모집 지원 도착 (호스트가 받는다). referenceId = 지원 ID, parentReferenceId = 모집글 ID */
    APPEARANCE_APPLICATION_SUBMITTED,
    /** 단체방 초대 안내. 보낸 사람 = 호스트. 값은 {@link GroupMembersInvitedData} */
    GROUP_MEMBERS_INVITED,
    /** 단체방 강퇴 안내. 보낸 사람 = 호스트. 값은 {@link GroupMemberKickedData} */
    GROUP_MEMBER_KICKED,
    /** 단체방 나가기 안내. 보낸 사람 = 나간 회원. 값은 {@link GroupMemberLeftData} */
    GROUP_MEMBER_LEFT,
    /** 단체방 폐쇄 안내(읽기 전용 전환). 보낸 사람 = 호스트. 값은 {@link GroupRoomClosedData} */
    GROUP_ROOM_CLOSED,
    /**
     * 호스트가 방을 운영할 수 없게 됨(탈퇴·자격 상실, 세부 사유는 남기지 않는다). 방이 읽기 전용으로 바뀐다. 보낸 사람 = 호스트.
     * 값은 {@link GroupHostUnavailableData}
     */
    GROUP_HOST_UNAVAILABLE,
    /** 단체방 일시 중지. 전환 기능이 생길 때 남기기 시작한다 */
    GROUP_ROOM_SUSPENDED,
    /** 단체방 재개. 전환 기능이 생길 때 남기기 시작한다 */
    GROUP_ROOM_RESUMED
}
