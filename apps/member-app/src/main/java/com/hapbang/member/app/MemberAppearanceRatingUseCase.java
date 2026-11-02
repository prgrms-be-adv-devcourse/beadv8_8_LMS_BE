package com.hapbang.member.app;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hapbang.member.domain.AppearanceRating;
import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.out.AppearanceRatingRepository;
import com.hapbang.member.out.AppearanceReviewReceiptRepository;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 회원 출연 평점 (#555). 출연 앱이 공개한 리뷰를 받은 회원의 평점에 더하고, 평점을 읽는다.
 * 리뷰는 수정·삭제가 없어 더하기만 한다(빼는 이벤트가 없다)
 */
@Service
@RequiredArgsConstructor
public class MemberAppearanceRatingUseCase {

    private final AppearanceReviewReceiptRepository receiptRepository;
    private final AppearanceRatingRepository ratingRepository;

    /**
     * 공개된 리뷰 하나를 받은 회원의 평점에 더한다. 이미 반영한 리뷰면 아무것도 하지 않는다.
     * 반영 기록과 합계는 같은 트랜잭션이어야 한다(호출하는 Facade가 연다)
     *
     * @return 이번에 반영했으면 true, 이미 반영한 리뷰면 false
     */
    public boolean apply(AppearanceReviewPublishedEvent event) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = receiptRepository.insertIfAbsent(event.reviewId(), event.appearanceId(),
                event.reviewerUserId(), event.revieweeUserId(), event.revieweeRole().name(), event.rating(),
                event.publishedAt(), now);
        if (inserted == 0) {
            return false;
        }
        int rating = event.rating();
        switch (event.revieweeRole()) {
            case HOST -> ratingRepository.add(event.revieweeUserId(), rating, 1, 0, 0, now);
            case GUEST -> ratingRepository.add(event.revieweeUserId(), 0, 0, rating, 1, now);
        }
        return true;
    }

    /** 회원 한 명의 평점. 받은 리뷰가 없으면 모두 비어 있다 */
    public AppearanceRatingSummary find(Long userId) {
        return ratingRepository.findById(userId)
                .map(AppearanceRating::summary)
                .orElse(AppearanceRatingSummary.EMPTY);
    }

    /** 여러 회원의 평점을 한 번에 읽는다 (목록). 받은 리뷰가 없는 회원은 결과에 없다 */
    public Map<Long, AppearanceRatingSummary> findAll(Collection<Long> userIds) {
        return ratingRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppearanceRating::getUserId, AppearanceRating::summary));
    }
}
