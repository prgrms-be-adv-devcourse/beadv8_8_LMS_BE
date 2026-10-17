package com.hapbang.member.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.UpgradeRequestStatus;

import lombok.RequiredArgsConstructor;

/** 관리자 승급 심사 진입점. /admin 경로는 SecurityConfig도 함께 막는다 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MemberUpgradeReviewFacade {

    /** 오래된 신청부터 (같은 시각이면 먼저 만든 신청) */
    private static final Sort OLDEST_REQUEST_FIRST = Sort.by(Sort.Order.asc("requestedAt"), Sort.Order.asc("id"));

    private final MemberUpgradeReviewUseCase memberUpgradeReviewUseCase;

    /** 심사 목록 (정상 회원만, 승인된 채널 포함, 정렬은 고정) */
    @Transactional(readOnly = true)
    public Page<UpgradeRequestReview> findRequests(UpgradeRequestStatus status, Pageable pageable) {
        return memberUpgradeReviewUseCase.findRequests(status,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), OLDEST_REQUEST_FIRST));
    }

    /** 승인 */
    @Transactional
    public void approve(Long adminId, Long requestId) {
        memberUpgradeReviewUseCase.approve(adminId, requestId);
    }

    /** 반려 */
    @Transactional
    public void reject(Long adminId, Long requestId, String reason) {
        memberUpgradeReviewUseCase.reject(adminId, requestId, reason);
    }
}
