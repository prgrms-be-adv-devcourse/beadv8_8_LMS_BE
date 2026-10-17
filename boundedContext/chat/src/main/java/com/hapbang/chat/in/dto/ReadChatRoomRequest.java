package com.hapbang.chat.in.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * @param lastReadSequence 화면에 보여 준 가장 최근 메시지의 방 순번({@code roomSequence})
 */
public record ReadChatRoomRequest(
        @NotNull(message = "마지막으로 읽은 메시지 순번은 필수입니다.")
        @PositiveOrZero(message = "마지막으로 읽은 메시지 순번은 0 이상이어야 합니다.")
        Long lastReadSequence
) {
}
