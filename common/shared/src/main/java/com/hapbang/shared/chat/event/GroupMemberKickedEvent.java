package com.hapbang.shared.chat.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 호스트가 모집용 단체방에서 회원을 내보냈다 (알림 계약 M8). 알림센터가 내보내진 회원에게 강퇴를 알린다.
 * <p>
 * 참여 한 번(참여 세대)에 많아야 한 번 발행된다. 강퇴 기록 ID가 없고, 재초대 뒤 같은 회원이 다시 내보내질 수 있어 사건은
 * {@code <chatRoomId>:<kickedUserId>:<participationVersion>}으로 구분한다. 재초대하면 참여 세대가 늘어 다시 내보낸 사건의 ID가 다르다.
 * <p>
 * Kafka {@value ChatEventTopic#NAME} 토픽에 key 방 ID로 나간다. 최소 한 번 전달이라 받는 쪽은 사건 ID로 중복을 거른다.
 * 채팅 방 안의 강퇴 안내는 남은 참여자가 보지만, 이 이벤트의 받는 사람은 내보내진 회원이다(알림 결정 ND-043).
 *
 * @param chatRoomId           단체방 ID
 * @param recruitId            모집글 ID
 * @param title                모집글 제목(방이 가진 사본)
 * @param hostUserId           내보낸 호스트 회원 ID
 * @param kickedUserId         내보내진 회원 ID(받는 사람)
 * @param participationVersion 끝난 참여 세대(1부터, 재초대마다 1씩 는다)
 * @param kickedAt             강퇴 시각
 */
@Externalized(ChatEventTopic.NAME + "::#{#this.chatRoomId()}")
public record GroupMemberKickedEvent(
        Long chatRoomId,
        Long recruitId,
        String title,
        Long hostUserId,
        Long kickedUserId,
        int participationVersion,
        Instant kickedAt
) {
}
