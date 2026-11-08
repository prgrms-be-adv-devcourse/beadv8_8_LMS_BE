package com.hapbang.chat.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 단체방 폐쇄 안내({@link NotificationKind#GROUP_ROOM_CLOSED})의 값. 읽기 전용 방의 마지막 메시지다. 형식은 {@link NotificationData}를
 * 따른다.
 *
 * @param v              형식 버전
 * @param closedByUserId 폐쇄한 회원(호스트) ID
 */
@Schema(description = "단체방 폐쇄 안내(GROUP_ROOM_CLOSED)의 값")
public record GroupRoomClosedData(
        @Schema(description = "형식 버전", example = "1") int v,
        @Schema(description = "폐쇄한 회원(호스트) ID", example = "1") Long closedByUserId
) {

    public static GroupRoomClosedData of(Long closedByUserId) {
        return new GroupRoomClosedData(NotificationData.VERSION, closedByUserId);
    }
}
