package com.hapbang.member.in;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.member.app.UpgradeRequestReview;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.UpgradeRequestStatus;

/** 관리자 승급 심사 목록 한 줄: 신청·회원·심사 정보 + 그 회원의 승인된 채널 (처리 전이면 reviewedAt·reviewedBy·rejectionReason은 null) */
public record UpgradeReviewResponse(Long requestId, Long memberId, String nickname, String requestReason,
                                    UpgradeRequestStatus status, LocalDateTime requestedAt,
                                    LocalDateTime reviewedAt, Long reviewedBy, String rejectionReason,
                                    List<ApprovedChannel> channels) {

    /** 승인된 채널: 관리자가 주소로 들어가 활동을 확인한다 */
    public record ApprovedChannel(ChannelPlatform platform, String channelName, String channelUrl, int followerCount) {

        static ApprovedChannel from(InfluencerChannel channel) {
            return new ApprovedChannel(channel.getPlatform(), channel.getChannelName(), channel.getChannelUrl(),
                    channel.getFollowerCount());
        }
    }

    public static UpgradeReviewResponse from(UpgradeRequestReview review) {
        InfluencerUpgradeRequest request = review.request();
        return new UpgradeReviewResponse(request.getId(), request.getUser().getId(), request.getUser().getNickname(),
                request.getRequestReason(), request.getStatus(), request.getRequestedAt(),
                request.getReviewedAt(), request.getReviewedBy(), request.getRejectionReason(),
                review.approvedChannels().stream().map(ApprovedChannel::from).toList());
    }
}
