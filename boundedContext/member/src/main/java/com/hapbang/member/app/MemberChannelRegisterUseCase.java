package com.hapbang.member.app;

import java.time.LocalDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/** 확인한 채널 등록. 자동 승인이면 유저 → 인플루언서로 바꾸고 UserModifiedEvent 발행 */
@Service
@RequiredArgsConstructor
public class MemberChannelRegisterUseCase {

    private final MemberSupport memberSupport;
    private final InfluencerChannelRepository influencerChannelRepository;
    private final ApplicationEventPublisher eventPublisher;

    // 다른 UseCase와 달리 여기에 트랜잭션을 둔다: Facade는 치지직 호출 때문에 트랜잭션 없이 이 메서드를 부른다
    @Transactional
    public ChannelConnectResult register(Long userId, ChannelPlatform platform, ChzzkChannel chzzkChannel) {
        User user = memberSupport.findActiveUser(userId);
        if (influencerChannelRepository.existsByPlatformAndChannelIdentifier(platform, chzzkChannel.channelId())) {
            throw new MemberException(MemberErrorCode.CHANNEL_ALREADY_REGISTERED);
        }

        InfluencerChannel channel = InfluencerChannel.register(user, platform, chzzkChannel.channelId(),
                chzzkChannel.channelName(), chzzkChannel.followerCount(), LocalDateTime.now());
        try {
            // 동시에 같은 채널을 등록하면 위 검사를 둘 다 통과할 수 있어 DB 유니크 제약으로 한 번 더 막는다
            influencerChannelRepository.saveAndFlush(channel);
        } catch (DataIntegrityViolationException e) {
            throw new MemberException(MemberErrorCode.CHANNEL_ALREADY_REGISTERED);
        }

        if (channel.isApproved() && user.getRole() == Role.USER) {
            user.changeRole(Role.INFLUENCER);
            eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
        }
        return new ChannelConnectResult(channel.getVerificationStatus(), user.getRole());
    }
}
