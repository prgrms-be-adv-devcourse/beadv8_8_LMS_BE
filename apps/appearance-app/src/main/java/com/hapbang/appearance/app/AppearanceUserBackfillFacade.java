package com.hapbang.appearance.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.hapbang.appearance.app.dto.AppearanceUserBackfillResDto;

import lombok.RequiredArgsConstructor;

/**
 * 관리자가 수동으로 실행하는 기존 회원 Backfill 진입점 (#394). 자동 실행(배치·기동 때 실행)은 두지 않는다.
 * <ul>
 *     <li>관리자 API는 두 겹으로 막는다: 보안 설정이 {@code /api/v1/appearances/admin/**}를 관리자만 통과시키고,
 *     여기서 {@code @PreAuthorize}로 Gateway가 넘긴 사용자 헤더의 역할을 확인한다.</li>
 *     <li>다른 관리자 API(HP 작업)와 달리 복제한 회원 정보로 관리자 자격을 다시 확인하지 않는다.
 *     Backfill 전에는 관리자 자신의 출연 회원이 없을 수 있기 때문이다.</li>
 *     <li>회원 내부 API 호출이 있어 트랜잭션을 열지 않는다(회원마다 UseCase가 짧은 트랜잭션을 연다).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AppearanceUserBackfillFacade {

    private final AppearanceUserBackfillUseCase appearanceUserBackfillUseCase;

    public AppearanceUserBackfillResDto backfill(long afterId, int limit) {
        return appearanceUserBackfillUseCase.run(afterId, limit);
    }
}
