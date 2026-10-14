package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureReview;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.domain.OrderItemStatus;
import com.hapbang.lecture.out.LectureReviewRepository;

/**
 * 리뷰 작성. 강의 상태와 관계없이 구매 확정 후의 본인 항목이면 쓸 수 있다(삭제된 강의도 구매자는 계속 보는 상품, D5).
 * 한 회원은 한 강의에 살아 있는 리뷰를 하나만 가진다.
 */
@Service
public class ReviewWriteUseCase {

    private final LectureReviewRepository lectureReviewRepository;
    private final LectureSupport lectureSupport;
    private final LectureOrderSupport lectureOrderSupport;
    private final Clock clock;

    public ReviewWriteUseCase(LectureReviewRepository lectureReviewRepository, LectureSupport lectureSupport,
                              LectureOrderSupport lectureOrderSupport, @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureReviewRepository = lectureReviewRepository;
        this.lectureSupport = lectureSupport;
        this.lectureOrderSupport = lectureOrderSupport;
        this.clock = clock;
    }

    public Long write(Long userId, Long lectureId, Long orderItemId, int rating, String content) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        OrderItem item = lectureOrderSupport.findItem(userId, orderItemId);
        if (!item.getLectureId().equals(lecture.getId())) {
            throw new LectureException(LectureErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LectureReview review = LectureReview.write(item, userId, rating, content, now);
        lectureReviewRepository.findAlive(lectureId, userId)
                .ifPresent(existing -> replaceReturned(existing, userId, now));
        // 같은 강의에 동시에 쓴 요청이 먼저 커밋했으면 부분 유일 인덱스에 걸린다. 커밋 전에 확인해 409로 바꾼다
        try {
            LectureReview saved = lectureReviewRepository.save(review);
            lectureReviewRepository.flush();
            return saved.getId();
        } catch (DataIntegrityViolationException exception) {
            throw new LectureException(LectureErrorCode.ALREADY_REVIEWED);
        }
    }

    /**
     * 반환된 항목의 리뷰는 목록·평균에 보이지 않아 작성자가 찾아 지울 수 없다. 다시 산 강의에 새 리뷰를 쓰면 대체한다 (D42).
     * 그 밖의 기존 리뷰가 있으면 {@code ALREADY_REVIEWED}다.
     */
    private void replaceReturned(LectureReview existing, Long userId, LocalDateTime now) {
        OrderItem reviewedItem = lectureOrderSupport.findItem(userId, existing.getOrderItemId());
        if (reviewedItem.getStatus() != OrderItemStatus.RETURNED) {
            throw new LectureException(LectureErrorCode.ALREADY_REVIEWED);
        }
        existing.delete(userId, now);
        // 부분 유일 인덱스 검사 전에 삭제 표시를 먼저 반영한다. INSERT가 UPDATE보다 먼저 실행되지 않게 한다
        lectureReviewRepository.flush();
    }
}
