package com.hapbang.shared.member.event;

import java.time.Instant;

import org.springframework.modulith.events.Externalized;

/**
 * 채널 인증 승인 이벤트 (#874, 알림 계약 ND-042). 회원의 채널이 자동 승인(팔로워 기준 이상, 등록하며 바로 또는 승인 요청 때)되거나
 * 관리자가 승인하면 member-app이 Kafka {@code member.events.v1} 토픽에 회원 ID를 key로 발행한다.
 * <p>
 * 역할이 바뀌지 않는 승인(이미 인플루언서인 회원의 채널)에도 나가므로, 채널 승인은 {@link UserModifiedEvent}의 역할 변경이 아니라 이
 * 이벤트로 알아낸다. 같은 채널이 반려 뒤 다시 승인될 수 있어 사건은 {@code <channelId>:<reviewSequence>}로 구분한다(ND-045).
 * 발행은 아웃박스라 같은 이벤트가 다시 올 수 있다. 쓰지 않는 앱은 모르는 종류로 무시한다 (ADR 0001).
 *
 * @param userId         채널 주인 회원 ID (받는 사람)
 * @param channelId      승인된 채널 ID
 * @param reviewSequence 심사 회차. 등록하며 자동 승인되면 1, 승인 요청마다 1씩 는다
 * @param autoApproved   자동 승인이면 true, 관리자 승인이면 false
 * @param approvedAt     승인한 시각
 */
@Externalized("member.events.v1::#{#this.userId()}")
public record InfluencerChannelApprovedEvent(Long userId, Long channelId, Integer reviewSequence,
                                             boolean autoApproved, Instant approvedAt) {
}
