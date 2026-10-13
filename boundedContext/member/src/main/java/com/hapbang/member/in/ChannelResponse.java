package com.hapbang.member.in;

import java.time.LocalDateTime;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;

/**
 * 회원 프로필의 채널 (다른 회원도 본다). 등록된 채널은 모두 본인 채널로 확인된 것이라 전부 보여 주고,
 * 채널 승인 절차 정보(상태·반려 사유)는 넣지 않는다
 */
public record ChannelResponse(ChannelPlatform platform, String channelName, String channelUrl,
                              int followerCount, LocalDateTime followerCountAt) {

    public static ChannelResponse from(InfluencerChannel channel) {
        return new ChannelResponse(channel.getPlatform(), channel.getChannelName(), channel.getChannelUrl(),
                channel.getFollowerCount(), channel.followerCountAt());
    }
}
