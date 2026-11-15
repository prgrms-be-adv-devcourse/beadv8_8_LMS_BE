package com.hapbang.shared.member.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 승급 반려 이벤트 (#874, 알림 계약 M6). 관리자가 인증된 인플루언서 승급 신청을 반려하면 member-app이 Kafka
 * {@code member.events.v1} 토픽에 회원 ID를 key로 발행한다.
 * <p>
 * 승급 신청은 1건이 1행이라 사건은 {@code upgradeRequestId}로 구분한다. 승급 승인은 이 이벤트가 아니라 {@link UserModifiedEvent}의
 * 역할 변경(인플루언서 → 인증된 인플루언서)으로 알아낸다. 발행은 아웃박스라 같은 이벤트가 다시 올 수 있다. 쓰지 않는 앱은 모르는 종류로
 * 무시한다 (ADR 0001).
 *
 * @param userId           신청한 회원 ID (받는 사람)
 * @param upgradeRequestId 반려된 승급 신청 ID
 * @param rejectionReason  반려 사유 (앞뒤 공백을 지운 500자 이내)
 * @param rejectedAt       반려한 시각
 */
@Externalized("member.events.v1::#{#this.userId()}")
public record InfluencerUpgradeRequestRejectedEvent(Long userId, Long upgradeRequestId, String rejectionReason,
                                                    Instant rejectedAt) {
}
