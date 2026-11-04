package com.hapbang.lecture.app.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.hapbang.lecture.out.LectureReviewStats;

/**
 * 강의 목록·상세에 보여 줄 평균 평점과 리뷰 수. 평균은 소수 첫째 자리까지 반올림하고 리뷰가 없으면 null이다.
 * 반환된 항목의 리뷰와 삭제된 리뷰는 빠진다 (D5).
 */
public record LectureRating(Double averageRating, long reviewCount) {

    public static final LectureRating NONE = new LectureRating(null, 0);

    public static LectureRating from(LectureReviewStats stats) {
        double rounded = BigDecimal.valueOf(stats.averageRating()).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new LectureRating(rounded, stats.reviewCount());
    }
}
