package com.hapbang.member.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.InfluencerChannelApprovedEvent;
import com.hapbang.shared.member.event.InfluencerChannelRejectedEvent;

import lombok.RequiredArgsConstructor;

/** 채널 조회·채널 심사 결과 반영(역할 변경·심사 이벤트) 공통 */
@Service
@RequiredArgsConstructor
public class MemberChannelSupport {

    private final InfluencerChannelRepository influencerChannelRepository;
    private final MemberSupport memberSupport;
    private final ApplicationEventPublisher eventPublisher;

    /** 내 채널만. 남의 채널이거나 없으면 CHANNEL_NOT_FOUND (남의 채널이 있는지도 알려 주지 않는다) */
    public InfluencerChannel findMyChannel(Long userId, Long channelId) {
        return influencerChannelRepository.findByIdAndUserIdAndDeletedAtIsNull(channelId, userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.CHANNEL_NOT_FOUND));
    }

    /** 승인 요청할 내 채널을 잠가서 조회 (#874). 남의 채널이거나 없으면 CHANNEL_NOT_FOUND */
    public InfluencerChannel findMyChannelForUpdate(Long userId, Long channelId) {
        return influencerChannelRepository.findMyChannelForUpdate(channelId, userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.CHANNEL_NOT_FOUND));
    }

    /** 승인·반려할 채널을 잠가서 조회. 없거나 삭제됐으면 CHANNEL_NOT_FOUND */
    public InfluencerChannel findChannelForUpdate(Long channelId) {
        return influencerChannelRepository.findByIdForUpdate(channelId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.CHANNEL_NOT_FOUND));
    }

    /** 심사 목록: 정상 회원의 채널만 */
    public Page<InfluencerChannel> findChannelsForReview(ChannelVerificationStatus status, Pageable pageable) {
        return influencerChannelRepository.findByVerificationStatusAndDeletedAtIsNullAndUserStatus(
                status, UserStatus.ACTIVE, pageable);
    }

    /** 회원의 채널 전부 (먼저 등록한 순) */
    public List<InfluencerChannel> findChannels(Long userId) {
        return influencerChannelRepository.findByUserIdAndDeletedAtIsNullOrderByIdAsc(userId);
    }

    /**
     * 등록·승인 요청·관리자 승인 직후의 결과 반영. 채널이 승인됐으면 채널 승인 이벤트를 내고(자동·관리자 모두, #874),
     * 아직 유저면 인플루언서로 바꾸고 UserModifiedEvent 발행 (이미 인플루언서면 역할은 그대로)
     */
    public ChannelVerificationResult applyResult(User user, InfluencerChannel channel) {
        boolean roleChanged = channel.isApproved() && user.getRole() == Role.USER;
        if (roleChanged) {
            memberSupport.changeRole(user, Role.INFLUENCER);
        }
        if (channel.isApproved()) {
            // 자동 승인은 심사한 관리자가 없다 (verifiedBy = null)
            eventPublisher.publishEvent(new InfluencerChannelApprovedEvent(user.getId(), channel.getId(),
                    channel.getReviewCount(), channel.getVerifiedBy() == null, toInstant(channel.getVerifiedAt())));
        }
        return new ChannelVerificationResult(channel.getVerificationStatus(), user.getRole(), roleChanged);
    }

    /** 관리자 반려 직후 채널 반려 이벤트 발행 (#874) */
    public void publishRejected(InfluencerChannel channel) {
        eventPublisher.publishEvent(new InfluencerChannelRejectedEvent(channel.getUser().getId(), channel.getId(),
                channel.getReviewCount(), channel.getRejectionReason(), toInstant(channel.getVerifiedAt())));
    }

    /** 엔티티는 서버 시간대(KST)의 LocalDateTime, 회원 이벤트는 Instant를 쓴다 */
    private static Instant toInstant(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
