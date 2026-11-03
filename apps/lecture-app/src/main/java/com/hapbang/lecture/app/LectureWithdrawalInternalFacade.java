package com.hapbang.lecture.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 탈퇴 확인 내부 조회 진입점 (#625, 회원 #593이 호출). 내부 API 키로 인증한 호출만 쓴다.
 * 회원 ID는 호출한 모듈이 인증한 회원의 것이다. 계약은 docs/api/internal-withdrawals.md 3장
 */
@Service
@RequiredArgsConstructor
// 내부 API 보안 체인(global InternalApiSecurityConfig)이 키를 검사하고, 회원 로그인이 이 진입점에 오지 못하게 한 번 더 막는다
@PreAuthorize("hasRole('INTERNAL')")
public class LectureWithdrawalInternalFacade {

    private final LectureWithdrawalBlockQuery lectureWithdrawalBlockQuery;

    @Transactional(readOnly = true)
    public WithdrawalBlocksDto getBlocks(Long userId) {
        return lectureWithdrawalBlockQuery.getBlocks(userId);
    }
}
