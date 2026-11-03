package com.hapbang.chat.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 단체방 초대 요청의 회원 한 명 결과.
 *
 * @param reason {@link ChatGroupInviteResult#FAILED}일 때의 사유. 그 밖에는 null
 */
@Schema(description = "단체방 초대의 회원별 결과")
public record ChatGroupInviteOutcome(
        @Schema(description = "회원 ID", example = "2") Long userId,
        @Schema(description = "JOINED(참여함)·ALREADY_MEMBER(이미 참여 중)·FAILED(참여하지 못함)") ChatGroupInviteResult result,
        @Schema(description = "실패 사유. FAILED가 아니면 null. NOT_APPLICANT(지원 이력 없음)·CHAT_NOT_ALLOWED(채팅할 수 없는 회원)·"
                + "ROOM_FULL(정원 초과)") ChatGroupInviteFailure reason
) {

    public static ChatGroupInviteOutcome joined(Long userId) {
        return new ChatGroupInviteOutcome(userId, ChatGroupInviteResult.JOINED, null);
    }

    public static ChatGroupInviteOutcome alreadyMember(Long userId) {
        return new ChatGroupInviteOutcome(userId, ChatGroupInviteResult.ALREADY_MEMBER, null);
    }

    public static ChatGroupInviteOutcome failed(Long userId, ChatGroupInviteFailure reason) {
        return new ChatGroupInviteOutcome(userId, ChatGroupInviteResult.FAILED, reason);
    }
}
