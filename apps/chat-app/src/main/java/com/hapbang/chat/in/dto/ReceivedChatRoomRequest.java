package com.hapbang.chat.in.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 누적 수신 확인 요청. 회원은 로그인 세션으로 정하고 요청 값으로 받지 않는다.
 *
 * @param participationVersion 수신을 확인한 참여 세대(메시지 이력 응답의 {@code participationVersion})
 * @param receivedSequence     현재 참여 세대의 가시 범위에서 빠짐없이 반영한 연속 구간의 끝 방 순번({@code roomSequence})
 */
public record ReceivedChatRoomRequest(
        @NotNull(message = "참여 세대는 필수입니다.")
        @Positive(message = "참여 세대는 1 이상이어야 합니다.")
        Integer participationVersion,
        @NotNull(message = "수신 확인 순번은 필수입니다.")
        @PositiveOrZero(message = "수신 확인 순번은 0 이상이어야 합니다.")
        Long receivedSequence
) {
}
