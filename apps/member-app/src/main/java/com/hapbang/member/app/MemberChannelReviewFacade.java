package com.hapbang.member.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;

import lombok.RequiredArgsConstructor;

/** 관리자 채널 심사 진입점. /admin 경로는 SecurityConfig도 함께 막는다 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MemberChannelReviewFacade {

    /** 오래된 신청부터 (같은 시각이면 먼저 등록된 채널) */
    private static final Sort OLDEST_REQUEST_FIRST = Sort.by(Sort.Order.asc("requestedAt"), Sort.Order.asc("id"));

    private final MemberChannelSupport memberChannelSupport;
    private final MemberChannelReviewUseCase memberChannelReviewUseCase;

    /** 심사 목록 (정상 회원 채널만, 정렬은 고정) */
    @Transactional(readOnly = true)
    public Page<InfluencerChannel> findChannels(ChannelVerificationStatus status, Pageable pageable) {
        return memberChannelSupport.findChannelsForReview(status,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), OLDEST_REQUEST_FIRST));
    }

    /** 승인 */
    @Transactional
    public ChannelVerificationResult approve(Long adminId, Long channelId) {
        return memberChannelReviewUseCase.approve(adminId, channelId);
    }

    /** 반려 */
    @Transactional
    public void reject(Long adminId, Long channelId, String reason) {
        memberChannelReviewUseCase.reject(adminId, channelId, reason);
    }
}
