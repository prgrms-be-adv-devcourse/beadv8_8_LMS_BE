package com.hapbang.member.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.User;

import lombok.RequiredArgsConstructor;

/** 채널 승인 요청. 다시 조회한 팔로워 수가 기준 이상이면 자동 승인 (유저 → 인플루언서), 아니면 관리자 심사 대기 */
@Service
@RequiredArgsConstructor
public class MemberChannelApprovalUseCase {

    private final MemberSupport memberSupport;
    private final MemberChannelSupport memberChannelSupport;

    // 등록과 같은 이유로 여기에 트랜잭션을 둔다 (Facade가 치지직 호출 뒤 트랜잭션 없이 부른다)
    @Transactional
    public ChannelVerificationResult request(Long userId, Long channelId, int followerCount) {
        User user = memberSupport.findActiveUser(userId);
        // 트랜잭션 안에서 다시 조회: 치지직을 기다리는 사이 상태가 바뀌었을 수 있다 (도메인이 상태를 다시 검사)
        InfluencerChannel channel = memberChannelSupport.findMyChannel(userId, channelId);
        channel.requestApproval(followerCount, LocalDateTime.now());
        return memberChannelSupport.applyResult(user, channel);
    }
}
