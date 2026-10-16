package com.hapbang.appearance.app;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceEarningItemResDto;
import com.hapbang.appearance.app.dto.AppearanceEarningSummaryResDto;
import com.hapbang.appearance.domain.AppearanceEarningStatus;
import com.hapbang.appearance.domain.AppearanceEarningType;

import lombok.RequiredArgsConstructor;

/**
 * 출연 수익 내부 조회 진입점 (#301). Billing이 내부 API({@code /api/v1/internal/appearances/earnings/**})로만 부른다.
 * <ul>
 *     <li>역할 검사: 내부 인증(키)을 통과한 호출만 ({@code ROLE_INTERNAL}, 인증 필터·체인은 Billing 공통부). 회원 세션으로는 부를 수 없다.</li>
 *     <li>회원 ID를 호출한 쪽이 지정한다. 키가 곧 권한이므로, 로그인 회원 본인인지는 Billing이 확인한다.</li>
 *     <li>트랜잭션: 읽기 전용 하나 (목록과 제목을 한 시점 값으로).</li>
 *     <li>공통 계약 DTO(shared {@code billing.earning})가 들어오면 여기서 바꿔 담는다. 지금은 같은 모양의 출연 DTO를 돌려준다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class AppearanceEarningFacade {

    private final AppearanceEarningQueryUseCase appearanceEarningQueryUseCase;

    @Transactional(readOnly = true)
    public AppearanceEarningSummaryResDto summarize(Long memberId, AppearanceEarningType type) {
        return appearanceEarningQueryUseCase.summarize(memberId, type);
    }

    @Transactional(readOnly = true)
    public Page<AppearanceEarningItemResDto> find(Long memberId, Collection<AppearanceEarningStatus> statuses,
                                                  AppearanceEarningType type, Pageable pageable) {
        return appearanceEarningQueryUseCase.find(memberId, statuses, type, pageable);
    }
}
