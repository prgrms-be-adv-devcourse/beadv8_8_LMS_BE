package com.hapbang.lecture.out;

/** 강의 하나의 평균 평점(반올림 전)과 리뷰 수. 반환된 항목의 리뷰와 삭제된 리뷰는 빠진다 */
public record LectureReviewStats(Long lectureId, Double averageRating, Long reviewCount) {
}
