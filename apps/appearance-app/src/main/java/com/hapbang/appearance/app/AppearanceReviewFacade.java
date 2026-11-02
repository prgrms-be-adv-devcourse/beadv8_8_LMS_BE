package com.hapbang.appearance.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.app.dto.AppearanceReviewIdResDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 리뷰 API 진입점 (#512). 컨트롤러는 이 Facade만 부른다.
 * <ul>
 *     <li>역할 검사: 출연 거래처럼 인플루언서 이상(관리자 제외)만 쓴다. 출연은 인플루언서끼리만 하므로, 일반 유저는 리뷰를 쓸 수 없다.
 *     로그인 역할은 토큰 발급 시점 값이라, 당사자·정상 상태·지금 역할은 UseCase가 출연과 복제한 회원 정보로 다시 확인한다.</li>
 *     <li>트랜잭션: 기능 하나를 한 트랜잭션으로 여기서 연다 (작성과 블라인드 공개가 함께 반영된다).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('INFLUENCER', 'VERIFIED_INFLUENCER')")
public class AppearanceReviewFacade {

    private final AppearanceReviewWriteUseCase appearanceReviewWriteUseCase;

    @Transactional
    public AppearanceReviewIdResDto write(Long reviewerId, Long appearanceId, Integer rating, String content) {
        Long reviewId = appearanceReviewWriteUseCase.write(reviewerId, appearanceId, rating, content);
        return new AppearanceReviewIdResDto(reviewId);
    }
}
