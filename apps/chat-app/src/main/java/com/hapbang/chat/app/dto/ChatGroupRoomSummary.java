package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomStatusReason;

/**
 * 단체방 응답의 단체방 정보. 방 목록·상세는 참여자를 모두 싣지 않고 인원과 내 참여 세대만 싣는다(참여자는 별도 페이지, Q37).
 *
 * @param hostUserId           호스트 회원 ID. 메시지의 보낸 사람과 같으면 왕관을 표시한다
 * @param title                모집글 제목(생성 때 복사, 초대 후보 검색 때 갱신)
 * @param statusReason         읽기 전용 사유. 사용 중·일시 중지면 null
 * @param readOnlyAt           읽기 전용 전환 시각. 아니면 null
 * @param historyExpiresAt     참여자 조회 만료 시각(전환 시각 + 30×24시간). 이 시각부터 볼 수 없다. 아니면 null
 * @param memberCount          호스트를 포함한 활성 참여자 수
 * @param maxGuestMembers      게스트 정원(호스트 제외)
 * @param myParticipationVersion 조회한 회원의 현재 참여 세대. 구독·읽음·수신 확인에 쓴다. 관리자 기록 조회에서는 null
 */
public record ChatGroupRoomSummary(
        Long recruitId,
        Long hostUserId,
        String title,
        ChatRoomStatusReason statusReason,
        LocalDateTime readOnlyAt,
        LocalDateTime historyExpiresAt,
        int memberCount,
        int maxGuestMembers,
        Integer myParticipationVersion
) {

    public static ChatGroupRoomSummary of(ChatRoom room, Integer myParticipationVersion) {
        return new ChatGroupRoomSummary(room.getRecruitId(), room.getOwnerUserId(), room.getTitle(),
                room.getStatusReason(), room.getReadOnlyAt(), room.getHistoryExpiresAt(), room.getActiveMemberCount(),
                room.getMaxGuestMembers(), myParticipationVersion);
    }
}
