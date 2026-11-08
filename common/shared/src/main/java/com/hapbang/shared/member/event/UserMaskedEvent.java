package com.hapbang.shared.member.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 탈퇴 회원 개인정보 마스킹 이벤트 (#594). 탈퇴하고 복구 기한(30일, 지갑 장애로 복구가 실패했으면 7일 더)이 지나 member-app이
 * 회원 식별 정보를 파기한 뒤 Kafka {@code member.events.v1} 토픽에 회원 ID를 key로 발행한다.
 * <p>
 * 받는 앱은 이 회원의 파기 대상 정보(docs/policy.md 삭제 정책)를 지운다. 예: 지갑은 환전 계좌의 계좌번호·예금주, 출연은 호스트 채널 주소.
 * 날짜는 세지 않아도 된다. 마스킹한 회원은 다시 복구되지 않으므로 이 이벤트 뒤에 되돌릴 일은 없다.
 * 닉네임은 같은 때 나가는 {@link UserModifiedEvent}로 바뀐다. 발행은 아웃박스라 같은 이벤트가 다시 올 수 있어, 받는 쪽은 여러 번
 * 처리해도 같은 결과가 되게 만든다. 쓰지 않는 앱은 모르는 종류로 무시한다 (ADR 0001).
 *
 * @param userId   마스킹한 회원 ID
 * @param maskedAt 마스킹한 시각
 */
@Externalized("member.events.v1::#{#this.userId()}")
public record UserMaskedEvent(Long userId, Instant maskedAt) {
}
