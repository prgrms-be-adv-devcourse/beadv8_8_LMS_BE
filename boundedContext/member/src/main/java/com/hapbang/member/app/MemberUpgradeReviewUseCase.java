package com.hapbang.member.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.UpgradeRequestStatus;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.InfluencerUpgradeRequestRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/** 관리자 승급 심사: 목록(승인된 채널 포함)·승인(인증된 인플루언서로)·반려. 트랜잭션은 Facade에 있다 */
@Service
@RequiredArgsConstructor
public class MemberUpgradeReviewUseCase {

    private final InfluencerUpgradeRequestRepository influencerUpgradeRequestRepository;
    private final InfluencerChannelRepository influencerChannelRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 심사 목록 (정상 회원만). 목록에 나온 회원들의 승인된 채널을 한 번에 모아 붙인다 */
    public Page<UpgradeRequestReview> findRequests(UpgradeRequestStatus status, Pageable pageable) {
        Page<InfluencerUpgradeRequest> requests =
                influencerUpgradeRequestRepository.findByStatusAndUserStatus(status, UserStatus.ACTIVE, pageable);
        List<Long> userIds = requests.map(request -> request.getUser().getId()).stream().distinct().toList();
        Map<Long, List<InfluencerChannel>> channelsByUser = userIds.isEmpty() ? Map.of()
                : influencerChannelRepository.findByUserIdInAndVerificationStatusAndDeletedAtIsNullOrderByIdAsc(
                        userIds, ChannelVerificationStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(channel -> channel.getUser().getId()));
        return requests.map(request -> new UpgradeRequestReview(request,
                channelsByUser.getOrDefault(request.getUser().getId(), List.of())));
    }

    /** 승인: 인증된 인플루언서로 바꾸고 UserModifiedEvent (세션은 커밋 뒤 MemberSessionRoleListener가 갱신) */
    public void approve(Long adminId, Long requestId) {
        InfluencerUpgradeRequest request = findReviewable(requestId);
        User user = request.getUser();
        if (user.getRole() != Role.INFLUENCER) {
            throw new MemberException(MemberErrorCode.UPGRADE_MEMBER_NOT_INFLUENCER);
        }
        request.approve(adminId, LocalDateTime.now());
        user.changeRole(Role.VERIFIED_INFLUENCER);
        eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
    }

    /** 반려 (역할은 그대로) */
    public void reject(Long adminId, Long requestId, String reason) {
        findReviewable(requestId).reject(adminId, reason, LocalDateTime.now());
    }

    /** 잠가서 읽고, 신청한 회원이 정상 회원이 아니면 409 (목록을 거치지 않은 호출도 막는다) */
    private InfluencerUpgradeRequest findReviewable(Long requestId) {
        InfluencerUpgradeRequest request = influencerUpgradeRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.UPGRADE_REQUEST_NOT_FOUND));
        if (!request.getUser().isActive()) {
            throw new MemberException(MemberErrorCode.UPGRADE_REQUEST_MEMBER_INACTIVE);
        }
        return request;
    }
}
