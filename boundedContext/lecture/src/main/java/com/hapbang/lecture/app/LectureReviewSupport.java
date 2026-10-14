package com.hapbang.lecture.app;

import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.ReviewResponse;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureReview;
import com.hapbang.lecture.out.LectureReviewRepository;

import lombok.RequiredArgsConstructor;

/** 리뷰 조회. 응답 DTO 변환은 호출한 Facade의 트랜잭션 안에서 끝난다. */
@Service
@RequiredArgsConstructor
public class LectureReviewSupport {

    private final LectureReviewRepository lectureReviewRepository;
    private final LectureSupport lectureSupport;
    private final LectureUserNicknameSupport nicknameSupport;

    /** 살아 있는 리뷰. 없거나 삭제된 리뷰는 {@code REVIEW_NOT_FOUND} */
    public LectureReview findReview(Long reviewId) {
        return lectureReviewRepository.findByIdAndDeletedAtIsNull(reviewId)
                .orElseThrow(() -> new LectureException(LectureErrorCode.REVIEW_NOT_FOUND));
    }

    /**
     * 강의 상세를 볼 수 있는 회원만 조회한다(판매 중이 아니면 판매자·보유 구매자만, D42). 반환된 항목의 리뷰는 빠진다(D5).
     * 최신순이며 요청의 정렬은 무시한다.
     */
    public Page<ReviewResponse> getReviews(Long userId, Long lectureId, Pageable pageable) {
        lectureSupport.findViewableLecture(userId, lectureId);
        Page<LectureReview> reviews = lectureReviewRepository.findVisible(lectureId,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        Map<Long, String> nicknames = nicknameSupport.of(reviews.map(LectureReview::getReviewerUserId).toSet());
        return reviews.map(review -> ReviewResponse.of(review, nicknames.get(review.getReviewerUserId())));
    }
}
