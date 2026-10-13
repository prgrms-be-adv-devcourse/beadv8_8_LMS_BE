package com.hapbang.member.app;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/** 채널 조회·채널 승인 후 역할 변경 공통 */
@Service
@RequiredArgsConstructor
public class MemberChannelSupport {

    private final InfluencerChannelRepository influencerChannelRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 내 채널만. 남의 채널이거나 없으면 CHANNEL_NOT_FOUND (남의 채널이 있는지도 알려 주지 않는다) */
    public InfluencerChannel findMyChannel(Long userId, Long channelId) {
        return influencerChannelRepository.findByIdAndUserIdAndDeletedAtIsNull(channelId, userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.CHANNEL_NOT_FOUND));
    }

    /** 회원의 채널 전부 (먼저 등록한 순) */
    public List<InfluencerChannel> findChannels(Long userId) {
        return influencerChannelRepository.findByUserIdAndDeletedAtIsNullOrderByIdAsc(userId);
    }

    /** 채널이 승인됐고 아직 유저면 인플루언서로 바꾸고 UserModifiedEvent 발행 (이미 인플루언서면 그대로) */
    public ChannelVerificationResult applyResult(User user, InfluencerChannel channel) {
        boolean roleChanged = channel.isApproved() && user.getRole() == Role.USER;
        if (roleChanged) {
            user.changeRole(Role.INFLUENCER);
            eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
        }
        return new ChannelVerificationResult(channel.getVerificationStatus(), user.getRole(), roleChanged);
    }
}
