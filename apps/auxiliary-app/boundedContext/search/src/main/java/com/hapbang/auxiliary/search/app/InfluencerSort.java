package com.hapbang.auxiliary.search.app;

/** 인플루언서 검색 순서(#771, #767 Q16) */
public enum InfluencerSort {
    /** 기본. 검색어가 있으면 관련도순, 없으면 팔로워 많은 순(분야를 골랐으면 분야 일치 수 → 팔로워) */
    DEFAULT,
    /** {@code sort=followers,desc}: 팔로워 많은 순 */
    FOLLOWERS,
    /** {@code sort=rating,desc}: 출연 평점 높은 순(베이지안 평균) */
    RATING
}
