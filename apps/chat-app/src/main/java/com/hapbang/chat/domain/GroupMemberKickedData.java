package com.hapbang.chat.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 단체방 강퇴 안내({@link NotificationKind#GROUP_MEMBER_KICKED})의 값. 형식은 {@link NotificationData}를 따른다.
 *
 * @param v              형식 버전
 * @param kickedByUserId 내보낸 회원(호스트) ID
 * @param kickedUserId   내보내진 회원 ID
 */
@Schema(description = "단체방 강퇴 안내(GROUP_MEMBER_KICKED)의 값")
public record GroupMemberKickedData(
        @Schema(description = "형식 버전", example = "1") int v,
        @Schema(description = "내보낸 회원(호스트) ID", example = "1") Long kickedByUserId,
        @Schema(description = "내보내진 회원 ID", example = "2") Long kickedUserId
) {

    public static GroupMemberKickedData of(Long kickedByUserId, Long kickedUserId) {
        return new GroupMemberKickedData(NotificationData.VERSION, kickedByUserId, kickedUserId);
    }
}
