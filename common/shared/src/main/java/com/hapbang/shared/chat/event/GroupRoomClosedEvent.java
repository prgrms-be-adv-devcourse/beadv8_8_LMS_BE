package com.hapbang.shared.chat.event;

import java.time.Instant;
import java.util.List;

import org.springframework.modulith.events.Externalized;

/**
 * 호스트가 모집용 단체방을 폐쇄했다(읽기 전용 전환) (알림 계약 M8). 알림센터가 남은 참여자에게 폐쇄를 알린다.
 * <p>
 * 방마다 한 번 발행된다(이미 폐쇄한 방을 다시 폐쇄하면 내지 않는다). 사건은 방 ID로 구분한다. 폐쇄 시점의 참여자 전원(호스트 포함)을
 * 담는다. 폐쇄한 호스트를 받는 사람에서 빼는 일은 받는 쪽이 한다. 호스트 자격 상실로 읽기 전용이 된 방은 이 이벤트를 내지 않는다.
 * <p>
 * Kafka {@value ChatEventTopic#NAME} 토픽에 key 방 ID로 나간다. 최소 한 번 전달이라 받는 쪽은 사건 ID로 중복을 거른다.
 *
 * @param chatRoomId    단체방 ID
 * @param recruitId     모집글 ID
 * @param title         모집글 제목(방이 가진 사본)
 * @param hostUserId    폐쇄한 호스트 회원 ID
 * @param memberUserIds 폐쇄 시점의 참여자 회원 ID(호스트 포함). 회원 ID 오름차순이다
 * @param closedAt      폐쇄 시각
 */
@Externalized(ChatEventTopic.NAME + "::#{#this.chatRoomId()}")
public record GroupRoomClosedEvent(
        Long chatRoomId,
        Long recruitId,
        String title,
        Long hostUserId,
        List<Long> memberUserIds,
        Instant closedAt
) {

    public GroupRoomClosedEvent {
        memberUserIds = List.copyOf(memberUserIds);
    }
}
