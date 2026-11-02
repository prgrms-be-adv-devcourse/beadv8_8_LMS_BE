package com.hapbang.lecture.app.dto;

/**
 * 강의 목록 정렬. 기본은 최신순. Swagger 설명은 쿼리 파라미터에 둔다({@code ApiV1LectureSpec#getLectures}).
 */
public enum LectureSort {
    /** 등록일 최신순 */
    LATEST,
    /** 가격 오름차순. 같으면 최신순 */
    PRICE_ASC,
    /** 가격 내림차순. 같으면 최신순 */
    PRICE_DESC,
    /** 평균 평점 내림차순 → 리뷰 수 내림차순 → 최신순. 리뷰가 없는 강의는 맨 뒤 */
    RATING
}
