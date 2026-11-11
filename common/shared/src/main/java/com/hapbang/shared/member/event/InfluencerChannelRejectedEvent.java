package com.hapbang.shared.member.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 채널 인증 반려 이벤트 (#874, 알림 계약 M6). 관리자가 심사 대기 채널을 반려하면 member-app이 Kafka {@code member.events.v1} 토픽에
 * 회원 ID를 key로 발행한다.
 * <p>
 * 회원은 반려된 채널로 다시 승인 요청할 수 있어 같은 채널이 여러 번 반려될 수 있다. 사건은 {@code <channelId>:<reviewSequence>}로
 * 구분한다(ND-045). 발행은 아웃박스라 같은 이벤트가 다시 올 수 있다. 쓰지 않는 앱은 모르는 종류로 무시한다 (ADR 0001).
 *
 * @param userId          채널 주인 회원 ID (받는 사람)
 * @param channelId       반려된 채널 ID
 * @param reviewSequence  심사 회차. 승인 요청마다 1씩 는다
 * @param rejectionReason 반려 사유 (앞뒤 공백을 지운 500자 이내)
 * @param rejectedAt      반려한 시각
 */
@Externalized("member.events.v1::#{#this.userId()}")
public record InfluencerChannelRejectedEvent(Long userId, Long channelId, Integer reviewSequence,
                                             String rejectionReason, Instant rejectedAt) {
}
