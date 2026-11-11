package com.hapbang.auxiliary.search.influencer.app;

import java.util.List;

/**
 * 인플루언서 검색 조건(#771). 값은 Facade가 다듬은 뒤다.
 *
 * @param keyword           검색어(닉네임·채널 이름·분야 이름·자기소개). 없으면 null
 * @param contentCategories 콘텐츠 분야 코드. 하나라도 가진 사람만(OR), 많이 맞을수록 위. 없으면 빈 목록
 * @param verified          인증 여부. true면 인증된 인플루언서만, false면 인증 안 된 인플루언서만, null이면 모두
 * @param minFollowers      팔로워 최소값(가장 큰 채널 기준). null이면 조건 없음
 * @param minRating         출연 평점 최소값(원래 평균). 리뷰가 없는 사람은 빠진다. null이면 조건 없음
 * @param sort              순서
 * @param page              페이지 번호(0부터)
 * @param size              페이지 크기
 */
public record InfluencerSearchCondition(String keyword, List<String> contentCategories, Boolean verified,
                                        Long minFollowers, Double minRating, InfluencerSort sort, int page, int size) {

    public InfluencerSearchCondition {
        contentCategories = List.copyOf(contentCategories);
    }

    /** 앞에서 몇 건을 건너뛰는지 */
    public long offset() {
        return (long) page * size;
    }
}
