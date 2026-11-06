package com.hapbang.appearance.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.appearance.dto.AppearanceReviewSyncPageDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncPageDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 동기화 내부 조회 진입점 (#713). 다른 모듈(AI 추천)이 이벤트 누락을 바로잡을 때 내부 API로만 부른다
 * (shared {@code AppearanceApiClient}, docs/api/internal-appearance-sync.md).
 * <ul>
 *     <li>역할 검사: 내부 인증(키)을 통과한 호출만 ({@code ROLE_INTERNAL}). 회원 로그인이 이 진입점에 오지 못하게 한 번 더 막는다
 *     (리뷰 내부 조회와 같음).</li>
 *     <li>afterId·size 범위 검사는 API 명세의 제약이 한다.</li>
 *     <li>트랜잭션: 읽기 전용 하나.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class AppearanceSyncInternalFacade {

    private final AppearanceSyncQueryUseCase appearanceSyncQueryUseCase;

    @Transactional(readOnly = true)
    public AppearanceSyncPageDto findAppearances(Long afterId, int size) {
        return appearanceSyncQueryUseCase.findAppearances(afterId, size);
    }

    @Transactional(readOnly = true)
    public AppearanceReviewSyncPageDto findPublishedReviews(Long afterId, int size) {
        return appearanceSyncQueryUseCase.findPublishedReviews(afterId, size);
    }
}
