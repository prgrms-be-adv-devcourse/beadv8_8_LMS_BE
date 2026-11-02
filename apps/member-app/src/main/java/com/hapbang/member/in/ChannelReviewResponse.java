package com.hapbang.member.in;

import java.time.LocalDateTime;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;

/** 관리자 심사 목록 한 줄: 채널·신청 회원·심사 정보 (처리 전이면 verifiedAt·verifiedBy·rejectionReason은 null) */
public record ChannelReviewResponse(Long channelId, Long memberId, String nickname,
                                    ChannelPlatform platform, String channelName, String channelUrl,
                                    int followerCount, LocalDateTime followerCountAt,
                                    ChannelVerificationStatus status, LocalDateTime requestedAt,
                                    LocalDateTime verifiedAt, Long verifiedBy, String rejectionReason) {

    public static ChannelReviewResponse from(InfluencerChannel channel) {
        return new ChannelReviewResponse(channel.getId(), channel.getUser().getId(), channel.getUser().getNickname(),
                channel.getPlatform(), channel.getChannelName(), channel.getChannelUrl(),
                channel.getFollowerCount(), channel.getFollowerCountUpdatedAt(),
                channel.getVerificationStatus(), channel.getRequestedAt(),
                channel.getVerifiedAt(), channel.getVerifiedBy(), channel.getRejectionReason());
    }
}
