package com.hapbang.billing.app;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.billing.domain.EarningStatus;

import lombok.RequiredArgsConstructor;

// 로그인한 회원 자신의 지급 예정 수익(강의 판매·출연) 통합 조회 진입점 (#295). 수익이 없는 유저도 조회할 수 있다(0원·빈 목록).
// 관리자는 HP를 쓰지 않으므로 지갑 API와 같이 403이다. 내부 HTTP 호출이 있어 트랜잭션을 열지 않는다.
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')")
public class EarningFacade {

    private final EarningQueryUseCase earningQueryUseCase;

    public EarningSummaryResult summary(Long userId) {
        return earningQueryUseCase.summary(userId);
    }

    public Page<EarningItemResult> list(Long userId, Collection<EarningStatus> statuses, int page, int size) {
        return earningQueryUseCase.list(userId, statuses, page, size);
    }
}
