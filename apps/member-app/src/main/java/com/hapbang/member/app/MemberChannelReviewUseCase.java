package com.hapbang.member.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;

import lombok.RequiredArgsConstructor;

/** 관리자 채널 심사: 승인(유저면 인플루언서로)·반려. 트랜잭션은 Facade에 있다 */
@Service
@RequiredArgsConstructor
public class MemberChannelReviewUseCase {

    private final MemberChannelSupport memberChannelSupport;

    /** 승인 후 F4와 같이 역할 변경·이벤트 (이미 인플루언서면 역할은 그대로, 채널 승인 이벤트는 낸다) */
    public ChannelVerificationResult approve(Long adminId, Long channelId) {
        InfluencerChannel channel = findReviewable(channelId);
        channel.approve(adminId, LocalDateTime.now());
        return memberChannelSupport.applyResult(channel.getUser(), channel);
    }

    /** 반려 (역할은 그대로). 채널 반려 이벤트를 낸다 (#874) */
    public void reject(Long adminId, Long channelId, String reason) {
        InfluencerChannel channel = findReviewable(channelId);
        channel.reject(adminId, reason, LocalDateTime.now());
        memberChannelSupport.publishRejected(channel);
    }

    /** 잠가서 읽고, 채널 주인이 정상 회원이 아니면 409 (목록을 거치지 않은 호출도 막는다) */
    private InfluencerChannel findReviewable(Long channelId) {
        InfluencerChannel channel = memberChannelSupport.findChannelForUpdate(channelId);
        if (!channel.getUser().isActive()) {
            throw new MemberException(MemberErrorCode.CHANNEL_OWNER_INACTIVE);
        }
        return channel;
    }
}
