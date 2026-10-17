package com.hapbang.lecture.app;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.dto.LectureEarningItemDto;
import com.hapbang.shared.lecture.dto.LectureEarningSummaryDto;

import lombok.RequiredArgsConstructor;

/**
 * 판매 수익 내부 조회 진입점 (#300, billing #295가 호출). 내부 API 키로 인증한 호출만 쓴다.
 * 회원 ID는 호출한 모듈이 인증한 회원의 것이고, 그 회원이 판매자인 주문 항목만 본다.
 */
@Service
@RequiredArgsConstructor
// 내부 API 보안 체인(global InternalApiSecurityConfig)이 키를 검사하고, 회원 세션이 이 진입점에 오지 못하게 한 번 더 막는다
@PreAuthorize("hasRole('INTERNAL')")
public class LectureEarningInternalFacade {

    private final LectureEarningSupport lectureEarningSupport;

    @Transactional(readOnly = true)
    public LectureEarningSummaryDto getSummary(Long sellerUserId) {
        return lectureEarningSupport.getSummary(sellerUserId);
    }

    @Transactional(readOnly = true)
    public Page<LectureEarningItemDto> getEarnings(Long sellerUserId, Collection<LectureEarningStatus> statuses,
                                                   Pageable pageable) {
        return lectureEarningSupport.getEarnings(sellerUserId, statuses, pageable);
    }
}
