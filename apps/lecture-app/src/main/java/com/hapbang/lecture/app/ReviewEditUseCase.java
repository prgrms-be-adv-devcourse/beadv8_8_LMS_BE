package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;

/** 리뷰 수정. 작성자 본인이면 항목 상태와 관계없이 고칠 수 있다 */
@Service
public class ReviewEditUseCase {

    private final LectureReviewSupport lectureReviewSupport;
    private final Clock clock;

    public ReviewEditUseCase(LectureReviewSupport lectureReviewSupport, @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureReviewSupport = lectureReviewSupport;
        this.clock = clock;
    }

    public void edit(Long userId, Long reviewId, int rating, String content) {
        lectureReviewSupport.findReview(reviewId).edit(userId, rating, content, LocalDateTime.now(clock));
    }
}
