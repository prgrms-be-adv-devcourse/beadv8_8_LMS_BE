package com.hapbang.appearance.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 리뷰 내부 조회 진입점 (#514). 회원 모듈이 내부 API({@code /api/v1/internal/appearances/users/{userId}/reviews})로만 부른다
 * (shared {@code AppearanceApiClient}).
 * <ul>
 *     <li>역할 검사: 내부 인증(키)을 통과한 호출만 ({@code ROLE_INTERNAL}). 키 검사는 global 내부 API 보안 체인이 하고,
 *     회원 로그인이 이 진입점에 오지 못하게 한 번 더 막는다 (수익 내부 조회와 같음).</li>
 *     <li>공개된 리뷰만 주므로 누가 조회해도 되는 정보다. 회원 ID는 호출한 쪽이 지정한다.</li>
 *     <li>트랜잭션: 읽기 전용 하나.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class AppearanceReviewInternalFacade {

    private final AppearanceReviewQueryUseCase appearanceReviewQueryUseCase;

    @Transactional(readOnly = true)
    public Page<AppearanceReceivedReviewDto> findReceived(Long userId, Pageable pageable) {
        return appearanceReviewQueryUseCase.findReceived(userId, pageable);
    }
}
