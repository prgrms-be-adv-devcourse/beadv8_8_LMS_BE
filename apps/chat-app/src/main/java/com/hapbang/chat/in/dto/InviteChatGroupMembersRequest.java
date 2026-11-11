package com.hapbang.chat.in.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * @param requestId 멱등 키. 같은 초대를 다시 보낼 때(응답을 받지 못한 재시도) 같은 값을 쓴다. 새 초대는 새 값이다
 * @param userIds   초대할 회원 ID(요청 순서대로 빈자리를 채운다). 중복은 한 번만 처리한다
 */
public record InviteChatGroupMembersRequest(
        @NotNull(message = "요청 ID는 필수입니다.")
        UUID requestId,
        @NotEmpty(message = "초대할 회원을 한 명 이상 보내야 합니다.")
        List<@NotNull(message = "회원 ID는 필수입니다.") @Positive(message = "회원 ID는 양수여야 합니다.") Long> userIds
) {
}
