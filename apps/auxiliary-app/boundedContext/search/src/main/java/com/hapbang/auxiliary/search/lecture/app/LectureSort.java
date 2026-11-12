package com.hapbang.auxiliary.search.lecture.app;

/** 강의 검색 순서(#883 L10). 가격 순서는 강의 가격 칸이 생기면 더한다 */
public enum LectureSort {
    /** 기본. 검색어가 있으면 관련도순, 없으면 최신순 */
    DEFAULT,
    /** {@code sort=latest,desc}: 최신순(강의 ID 내림차순. 등록 시각 칸이 없고 ID가 등록 순서와 같다, #882 Q5, #883 Q1) */
    LATEST,
    /** {@code sort=rating,desc}: 평점 높은 순(베이지안 평균) */
    RATING
}
