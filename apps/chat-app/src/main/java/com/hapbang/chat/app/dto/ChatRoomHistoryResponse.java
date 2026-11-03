package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.domain.ChatRoomType;

/**
 * 관리자 분쟁 확인용 채팅방 기록 한 페이지. 종료된 방, 참여자별로 숨겨진 메시지, Soft Delete 된 메시지도 포함한다.
 * 메시지와 입퇴장 기록을 하나의 시간순 흐름(시각, 기록 → 메시지, ID)으로 나눠 내려준다.
 * {@code logs}·{@code messages}는 이 페이지 구간에 든 항목이며 각각 시간순이다. 합쳐 보려면 시각 순으로 섞는다.
 *
 * @param status     방 상태. 참여자 조회 기간이 끝난 단체방은 {@code EXPIRED}다(관리자는 계속 조회한다)
 * @param members    참여자(퇴장 포함). 단체방도 모두 싣는다
 * @param group      단체방 정보(모집글·호스트·읽기 전용 사유·조회 만료). 1:1 방은 null
 * @param nextCursor 다음 페이지 요청에 쓸 커서. 더 없으면 null
 */
public record ChatRoomHistoryResponse(
        Long chatRoomId,
        ChatRoomStatus status,
        LocalDateTime createdAt,
        LocalDateTime endedAt,
        List<ChatRoomMemberResponse> members,
        List<ChatRoomLogResponse> logs,
        List<ChatMessageHistoryResponse> messages,
        String nextCursor,
        ChatRoomType roomType,
        ChatGroupRoomSummary group
) {
}
