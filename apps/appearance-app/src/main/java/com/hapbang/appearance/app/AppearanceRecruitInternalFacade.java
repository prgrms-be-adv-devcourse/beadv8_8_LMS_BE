package com.hapbang.appearance.app;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceRecruitApplicantIdsResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitAppliedUserIdsResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitInternalResDto;

import lombok.RequiredArgsConstructor;

/**
 * 모집글·지원 이력 내부 조회 진입점 (#586). 채팅 앱이 모집용 단체방 생성·초대 후보 검색·초대 때
 * 내부 API({@code /api/v1/internal/appearances/recruits/**})로만 부른다.
 * <ul>
 *     <li>역할 검사: 내부 인증(키)을 통과한 호출만 ({@code ROLE_INTERNAL}). 키 검사는 global 내부 API 보안 체인이 하고,
 *     회원 로그인이 이 진입점에 오지 못하게 한 번 더 막는다 (수익·리뷰 내부 조회와 같음).</li>
 *     <li>호스트 권한은 보지 않는다. 호출한 채팅이 응답의 {@code hostId}로 확인한다.</li>
 *     <li>트랜잭션: 읽기 전용 하나.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class AppearanceRecruitInternalFacade {

    private final AppearanceRecruitInternalQueryUseCase appearanceRecruitInternalQueryUseCase;

    @Transactional(readOnly = true)
    public AppearanceRecruitInternalResDto getRecruit(Long recruitId) {
        return appearanceRecruitInternalQueryUseCase.getRecruit(recruitId);
    }

    @Transactional(readOnly = true)
    public AppearanceRecruitApplicantIdsResDto findApplicantIds(Long recruitId, Long afterUserId, Integer size) {
        return appearanceRecruitInternalQueryUseCase.findApplicantIds(recruitId, afterUserId, size);
    }

    @Transactional(readOnly = true)
    public AppearanceRecruitAppliedUserIdsResDto findAppliedUserIds(Long recruitId, List<Long> userIds) {
        return appearanceRecruitInternalQueryUseCase.findAppliedUserIds(recruitId, userIds);
    }
}
