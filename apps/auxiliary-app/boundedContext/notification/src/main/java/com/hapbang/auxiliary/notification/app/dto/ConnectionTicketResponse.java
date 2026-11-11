package com.hapbang.auxiliary.notification.app.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** 연결 티켓 발급 응답(ND-056·ND-058) */
@Schema(description = "알림 웹소켓 연결 티켓")
public record ConnectionTicketResponse(
        @Schema(description = "일회용 티켓. 연결 주소의 ticket 쿼리로 보낸다") String ticket,
        @Schema(description = "티켓 유효 시간(초). 이 안에 연결해야 한다") long expiresInSeconds,
        @Schema(description = "연결이 열리면 서버가 이 시각에 닫는다(최대 발급 + 15분). 닫히면 새 티켓으로 다시 연결한다")
        Instant connectionExpiresAt
) {
}
