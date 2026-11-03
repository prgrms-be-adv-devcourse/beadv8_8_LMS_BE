package com.hapbang.appearance.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 탈퇴 확인 내부 진입점 (#624). 회원 앱이 내부 API({@code /api/v1/internal/appearances/users/{userId}/withdrawal-blocks})로만
 * 부른다 (shared {@code AppearanceApiClient#getWithdrawalBlocks}).
 * <ul>
 *     <li>역할 검사: 내부 인증(키)을 통과한 호출만 ({@code ROLE_INTERNAL}). 회원 로그인이 이 진입점에 오지 못하게 한 번 더 막는다.</li>
 *     <li>트랜잭션: 읽기 전용 하나 (여러 조건의 건수를 한 시점 값으로).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class AppearanceWithdrawalInternalFacade {

    private final AppearanceWithdrawalBlockQueryUseCase appearanceWithdrawalBlockQueryUseCase;

    @Transactional(readOnly = true)
    public WithdrawalBlocksDto findBlocks(Long userId) {
        return appearanceWithdrawalBlockQueryUseCase.find(userId);
    }
}
