package com.hapbang.member.app;

import java.util.List;

import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.InfluencerUpgradeRequest;

/** 관리자 심사 목록 한 줄: 승급 신청 + 그 회원의 승인된 채널 */
public record UpgradeRequestReview(InfluencerUpgradeRequest request, List<InfluencerChannel> approvedChannels) {
}
