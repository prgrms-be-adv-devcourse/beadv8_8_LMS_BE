package com.hapbang.auxiliary.search.influencer.app.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 인플루언서 검색 결과 한 줄(#771). 프로필 사진은 없다. FE가 회원 앱 사진 일괄 API(#770)로 채운다.
 *
 * @param id                회원 ID
 * @param nickname          닉네임
 * @param role              INFLUENCER 또는 VERIFIED_INFLUENCER
 * @param contentCategories 콘텐츠 분야(회원이 고른 순서). 이름을 아직 모르면 name이 null
 * @param bio               자기소개. 없으면 null
 * @param channelName       대표 채널(팔로워가 가장 많은 채널) 이름. 채널이 없거나 통계를 아직 못 받았으면 null
 * @param followerCount     대표 채널의 팔로워 수. 대개 10분 안에 회원 앱 값을 따라간다
 * @param appearanceRating  받은 출연 리뷰 전체 평점(원래 평균. 정렬에 쓰는 보정 평균이 아니다)
 */
public record InfluencerSearchItem(long id, String nickname, String role, List<Category> contentCategories, String bio,
                                   String channelName, long followerCount, Rating appearanceRating) {

    @Schema(description = "콘텐츠 분야")
    public record Category(@Schema(example = "MAPLESTORY") String code, @Schema(example = "메이플스토리") String name) {
    }

    @Schema(description = "출연 평점")
    public record Rating(@Schema(description = "평균(소수 첫째 자리). 리뷰가 없으면 null", example = "4.5") Double average,
                         @Schema(description = "리뷰 수", example = "12") long count) {
    }
}
