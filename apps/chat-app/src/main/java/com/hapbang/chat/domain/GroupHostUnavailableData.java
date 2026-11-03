package com.hapbang.chat.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 단체방 호스트 자격 상실 안내({@link NotificationKind#GROUP_HOST_UNAVAILABLE})의 값. 호스트가 탈퇴하거나 호스트 자격이 없는 역할이
 * 되어 방이 읽기 전용으로 바뀌었다. 탈퇴·강등 같은 세부 사유는 담지 않는다. 읽기 전용 방의 마지막 메시지다. 형식은 {@link NotificationData}를
 * 따른다.
 *
 * @param v          형식 버전
 * @param hostUserId 방을 운영할 수 없게 된 호스트 회원 ID
 */
@Schema(description = "단체방 호스트 자격 상실 안내(GROUP_HOST_UNAVAILABLE)의 값")
public record GroupHostUnavailableData(
        @Schema(description = "형식 버전", example = "1") int v,
        @Schema(description = "방을 운영할 수 없게 된 호스트 회원 ID", example = "1") Long hostUserId
) {

    public static GroupHostUnavailableData of(Long hostUserId) {
        return new GroupHostUnavailableData(NotificationData.VERSION, hostUserId);
    }
}
