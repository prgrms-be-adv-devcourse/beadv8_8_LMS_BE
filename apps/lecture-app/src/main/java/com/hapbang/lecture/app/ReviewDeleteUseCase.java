package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.LectureReview;

/** 리뷰 삭제(Soft Delete). 삭제한 뒤에는 같은 강의에 다시 쓸 수 있다 */
@Service
public class ReviewDeleteUseCase {

    private final LectureReviewSupport lectureReviewSupport;
    private final LectureSyncEvents lectureSyncEvents;
    private final Clock clock;

    public ReviewDeleteUseCase(LectureReviewSupport lectureReviewSupport, LectureSyncEvents lectureSyncEvents,
                               @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureReviewSupport = lectureReviewSupport;
        this.lectureSyncEvents = lectureSyncEvents;
        this.clock = clock;
    }

    public void delete(Long userId, Long reviewId) {
        LectureReview review = lectureReviewSupport.findReviewForUpdate(reviewId);
        review.delete(userId, LocalDateTime.now(clock));
        lectureSyncEvents.reviewChanged(review);
    }
}
