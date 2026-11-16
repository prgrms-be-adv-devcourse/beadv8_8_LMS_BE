package com.hapbang.shared.chat.event;

import java.time.Instant;
import java.util.List;

import org.springframework.modulith.events.Externalized;

/**
 * 호스트가 모집용 단체방에 회원을 초대해 그 회원들이 참여했다 (알림 계약 M8). 알림센터가 참여한 회원에게 초대를 알린다.
 * <p>
 * 초대 요청 하나에 많아야 한 번 발행된다. 이번 요청으로 실제 참여(JOINED)한 회원만 담고, 참여한 회원이 없으면 발행하지 않는다
 * (이미 참여 중이거나 실패한 회원은 빠진다). 같은 요청 ID의 재시도가 저장한 결과를 돌려줄 때도 발행하지 않는다.
 * 사건은 {@code <chatRoomId>:<inviteRequestId>}로 구분한다. 초대 요청 ID는 채팅이 초대 결과 기록마다 새로 발번하는 값이라,
 * 클라이언트가 같은 요청 ID(UUID)를 보존 기간 뒤 다시 써도 겹치지 않는다.
 * <p>
 * Kafka {@value ChatEventTopic#NAME} 토픽에 key 방 ID로 나간다. 최소 한 번 전달이라 받는 쪽은 사건 ID로 중복을 거른다.
 * 채팅 방 안의 초대 안내(시스템 메시지)와 이름이 같지만 받는 사람이 다르다(알림 결정 ND-043).
 *
 * @param chatRoomId      단체방 ID
 * @param inviteRequestId 초대 요청 ID(채팅이 발번한 초대 결과 기록 ID). 클라이언트의 멱등 키(UUID)가 아니다
 * @param recruitId       모집글 ID
 * @param title           모집글 제목(방이 가진 사본: 만들 때 복사하고 초대 후보 검색 때 갱신한다)
 * @param hostUserId      초대한 호스트 회원 ID
 * @param invitedUserIds  이번 요청으로 참여한 회원 ID(받는 사람). 요청 순서다
 * @param invitedAt       초대 시각
 */
@Externalized(ChatEventTopic.NAME + "::#{#this.chatRoomId()}")
public record GroupMembersInvitedEvent(
        Long chatRoomId,
        Long inviteRequestId,
        Long recruitId,
        String title,
        Long hostUserId,
        List<Long> invitedUserIds,
        Instant invitedAt
) {

    public GroupMembersInvitedEvent {
        invitedUserIds = List.copyOf(invitedUserIds);
    }
}
