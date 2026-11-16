package com.hapbang.auxiliary.search.lecture.out;

/**
 * 강의 하나의 평균 평점과 리뷰 수(#882 Q4). 평점에 반영되는 리뷰({@code counted})만 센다.
 *
 * @param average 평균 별점(반올림하지 않은 값). 리뷰가 없으면 {@code null}
 * @param count   리뷰 수
 */
public record LectureRating(Double average, long count) {

    /** 리뷰가 없는 강의 */
    public static final LectureRating NONE = new LectureRating(null, 0);
}
