package com.hapbang.chat.in.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * @param lastReadSequence     화면에 보여 준 가장 최근 메시지의 방 순번({@code roomSequence})
 * @param participationVersion 읽은 참여 세대(메시지 이력 응답의 {@code participationVersion}). 선택이며, 없으면 서버가 처리 시점의
 *                             현재 세대로 본다(전환 전 FE 호환)
 */
public record ReadChatRoomRequest(
        @NotNull(message = "마지막으로 읽은 메시지 순번은 필수입니다.")
        @PositiveOrZero(message = "마지막으로 읽은 메시지 순번은 0 이상이어야 합니다.")
        Long lastReadSequence,
        @Positive(message = "참여 세대는 1 이상이어야 합니다.")
        Integer participationVersion
) {
}
