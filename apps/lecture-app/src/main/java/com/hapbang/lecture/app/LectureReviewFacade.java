package com.hapbang.lecture.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.lecture.app.dto.ReviewResponse;

import lombok.RequiredArgsConstructor;

/**
 * 강의 리뷰 진입점 (설계 8.6). 목록은 로그인한 회원 모두, 작성·수정·삭제는 유저·인플루언서·인증된 인플루언서만 쓴다.
 * 관리자는 작성·수정·삭제 불가 (설계 9장, D16).
 */
@Service
@RequiredArgsConstructor
public class LectureReviewFacade {

    private static final String BUYER = "hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')";

    private final LectureReviewSupport lectureReviewSupport;
    private final ReviewWriteUseCase reviewWriteUseCase;
    private final ReviewEditUseCase reviewEditUseCase;
    private final ReviewDeleteUseCase reviewDeleteUseCase;

    /** 리뷰 목록 */
    @Transactional(readOnly = true)
    public Page<ReviewResponse> getReviews(Long userId, Long lectureId, Pageable pageable) {
        return lectureReviewSupport.getReviews(userId, lectureId, pageable);
    }

    /** 리뷰 작성. 만든 리뷰 ID를 돌려준다 */
    @PreAuthorize(BUYER)
    @Transactional
    public Long write(Long userId, Long lectureId, Long orderItemId, int rating, String content) {
        return reviewWriteUseCase.write(userId, lectureId, orderItemId, rating, content);
    }

    /** 리뷰 수정 */
    @PreAuthorize(BUYER)
    @Transactional
    public void edit(Long userId, Long reviewId, int rating, String content) {
        reviewEditUseCase.edit(userId, reviewId, rating, content);
    }

    /** 리뷰 삭제 */
    @PreAuthorize(BUYER)
    @Transactional
    public void delete(Long userId, Long reviewId) {
        reviewDeleteUseCase.delete(userId, reviewId);
    }
}
