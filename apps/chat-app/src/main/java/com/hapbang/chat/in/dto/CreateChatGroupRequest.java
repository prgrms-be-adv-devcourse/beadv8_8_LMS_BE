package com.hapbang.chat.in.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * @param recruitId 단체방을 만들 출연 모집글 ID. 호스트·제목·정원은 서버가 정한다
 */
public record CreateChatGroupRequest(
        @NotNull(message = "모집글 ID는 필수입니다.")
        @Positive(message = "모집글 ID는 양수여야 합니다.")
        Long recruitId
) {
}
