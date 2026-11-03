package com.hapbang.chat.domain;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 단체방 초대 안내({@link NotificationKind#GROUP_MEMBERS_INVITED})의 값. 한 번의 초대 요청에서 참여한 회원을 한 안내에 담는다.
 * 형식은 {@link NotificationData}를 따른다.
 *
 * @param v              형식 버전
 * @param inviterUserId  초대한 회원(호스트) ID
 * @param invitedUserIds 이번 요청으로 참여한 회원 ID. 요청 순서다
 */
@Schema(description = "단체방 초대 안내(GROUP_MEMBERS_INVITED)의 값")
public record GroupMembersInvitedData(
        @Schema(description = "형식 버전", example = "1") int v,
        @Schema(description = "초대한 회원(호스트) ID", example = "1") Long inviterUserId,
        @Schema(description = "이번 요청으로 참여한 회원 ID(요청 순서)", example = "[2, 3]") List<Long> invitedUserIds
) {

    public static GroupMembersInvitedData of(Long inviterUserId, List<Long> invitedUserIds) {
        return new GroupMembersInvitedData(NotificationData.VERSION, inviterUserId, List.copyOf(invitedUserIds));
    }
}
