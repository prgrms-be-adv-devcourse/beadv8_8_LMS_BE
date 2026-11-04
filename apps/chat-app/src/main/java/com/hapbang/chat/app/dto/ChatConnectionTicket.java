package com.hapbang.chat.app.dto;

import java.time.Instant;

import com.hapbang.shared.member.domain.Role;

/**
 * 웹소켓 연결 티켓(일회용)에 담은 회원 정보(설계 문서 6장). 핸드셰이크에서 티켓을 쓰면 이 값으로 연결의 회원·만료를 정한다.
 *
 * @param userId         회원 ID(Gateway 사용자 헤더 {@code X-User-Id})
 * @param role           티켓을 받을 때의 역할({@code X-User-Role}). 채팅 자격은 이것과 채팅 회원 복제본을 함께 본다
 * @param issuedAt       티켓을 발급한 시각. 연결의 인증 시각으로 쓰고, 로그아웃 표식보다 앞이면 그 연결을 닫는다
 * @param tokenExpiresAt 연결을 닫을 시각. access token의 {@code exp}를 따르되 발급 시각 + access token 수명을 넘지 않는다
 */
public record ChatConnectionTicket(Long userId, Role role, Instant issuedAt, Instant tokenExpiresAt) {
}
