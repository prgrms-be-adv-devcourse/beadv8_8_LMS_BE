package com.hapbang.chat.app.dto;

import java.time.Instant;

/**
 * 발급한 웹소켓 연결 티켓(설계 문서 6장, resync-frame.md "웹소켓 연결 티켓").
 *
 * @param ticket              연결 주소의 {@code ticket} 쿼리 값 또는 재인증 프레임의 {@code ticket} 헤더 값. 한 번만 쓸 수 있다
 * @param expiresIn           티켓이 유효한 시간(초)
 * @param connectionExpiresAt 이 티켓으로 연결하거나 재인증한 연결이 닫히는 시각(ISO-8601 UTC, 예: {@code 2026-11-02T01:15:00Z}).
 *                            access token의 {@code exp}를 따르되 발급 뒤 15분을 넘지 않는다
 */
public record ChatConnectionTicketResponse(String ticket, long expiresIn, Instant connectionExpiresAt) {
}
