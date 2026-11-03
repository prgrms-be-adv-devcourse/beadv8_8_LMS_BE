package com.hapbang.chat.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 단체방 나가기 안내({@link NotificationKind#GROUP_MEMBER_LEFT})의 값. 형식은 {@link NotificationData}를 따른다.
 *
 * @param v          형식 버전
 * @param leftUserId 나간 회원 ID
 */
@Schema(description = "단체방 나가기 안내(GROUP_MEMBER_LEFT)의 값")
public record GroupMemberLeftData(
        @Schema(description = "형식 버전", example = "1") int v,
        @Schema(description = "나간 회원 ID", example = "2") Long leftUserId
) {

    public static GroupMemberLeftData of(Long leftUserId) {
        return new GroupMemberLeftData(NotificationData.VERSION, leftUserId);
    }
}
