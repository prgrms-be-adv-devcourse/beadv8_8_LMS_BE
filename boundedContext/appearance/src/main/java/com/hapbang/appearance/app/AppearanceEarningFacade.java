package com.hapbang.appearance.app;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.domain.AppearanceEarningType;
import com.hapbang.shared.appearance.dto.AppearanceEarningItemDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningSummaryDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 수익 내부 조회 진입점 (#301). Billing이 내부 API({@code /api/v1/internal/appearances/earnings/**})로만 부른다
 * (shared {@code AppearanceApiClient}).
 * <ul>
 *     <li>역할 검사: 내부 인증(키)을 통과한 호출만 ({@code ROLE_INTERNAL}). 키 검사는 global 내부 API 보안 체인이 하고,
 *     회원 세션이 이 진입점에 오지 못하게 한 번 더 막는다.</li>
 *     <li>회원 ID를 호출한 쪽이 지정한다. 키가 곧 권한이므로, 로그인 회원 본인인지는 Billing이 확인한다.</li>
 *     <li>트랜잭션: 읽기 전용 하나 (목록과 제목을 한 시점 값으로).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class AppearanceEarningFacade {

    private final AppearanceEarningQueryUseCase appearanceEarningQueryUseCase;

    @Transactional(readOnly = true)
    public AppearanceEarningSummaryDto summarize(Long userId, AppearanceEarningType type) {
        return appearanceEarningQueryUseCase.summarize(userId, type);
    }

    @Transactional(readOnly = true)
    public Page<AppearanceEarningItemDto> find(Long userId, Collection<AppearanceEarningStatus> statuses,
                                               AppearanceEarningType type, Pageable pageable) {
        return appearanceEarningQueryUseCase.find(userId, statuses, type, pageable);
    }
}
