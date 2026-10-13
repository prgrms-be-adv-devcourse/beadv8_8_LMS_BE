package com.hapbang.member.in;

import java.time.LocalDateTime;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;

/** 내 채널 (본인만 본다). 승인 요청 버튼을 띄울지 판단하도록 상태·반려 사유를 넣는다 */
public record MyChannelResponse(Long id, ChannelPlatform platform, String channelName, String channelUrl,
                                int followerCount, LocalDateTime followerCountAt,
                                ChannelVerificationStatus status, String rejectionReason,
                                LocalDateTime requestedAt) {

    public static MyChannelResponse from(InfluencerChannel channel) {
        return new MyChannelResponse(channel.getId(), channel.getPlatform(), channel.getChannelName(),
                channel.getChannelUrl(), channel.getFollowerCount(), channel.followerCountAt(),
                channel.getVerificationStatus(), channel.getRejectionReason(), channel.getRequestedAt());
    }
}
