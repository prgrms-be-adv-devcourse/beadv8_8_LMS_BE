package com.hapbang.auxiliary.notification.app.dto;

import java.time.Instant;

import com.hapbang.shared.member.domain.Role;

/**
 * 연결 티켓에 담은 연결 정보(ND-028·ND-056). 티켓을 쓰면 이 값으로 연결의 회원과 연결 만료를 정한다.
 *
 * @param userId              회원 ID
 * @param role                발급 때의 역할
 * @param issuedAt            발급 시각
 * @param connectionExpiresAt 연결을 닫을 시각. 티켓 유효 시간(초 단위로 짧다)과 다른 값이다
 */
public record ConnectionTicket(Long userId, Role role, Instant issuedAt, Instant connectionExpiresAt) {
}
