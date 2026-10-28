package com.hapbang.lecture.app;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.app.dto.LectureRating;
import com.hapbang.lecture.out.LectureReviewRepository;
import com.hapbang.lecture.out.LectureReviewStats;

import lombok.RequiredArgsConstructor;

/** 강의의 평균 평점·리뷰 수. MVP에서는 조회할 때 집계하고(설계 4.7), 목록은 페이지의 강의를 한 번에 집계한다 */
@Component
@RequiredArgsConstructor
class LectureRatingSupport {

    private final LectureReviewRepository lectureReviewRepository;

    LectureRating of(Long lectureId) {
        return of(List.of(lectureId)).get(lectureId);
    }

    /** 리뷰가 없는 강의도 {@link LectureRating#NONE}으로 들어 있다 */
    Map<Long, LectureRating> of(Collection<Long> lectureIds) {
        if (lectureIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LectureRating> ratings = lectureReviewRepository.summarize(lectureIds).stream()
                .collect(Collectors.toMap(LectureReviewStats::lectureId, LectureRating::from));
        return lectureIds.stream().distinct()
                .collect(Collectors.toMap(id -> id, id -> ratings.getOrDefault(id, LectureRating.NONE)));
    }
}
