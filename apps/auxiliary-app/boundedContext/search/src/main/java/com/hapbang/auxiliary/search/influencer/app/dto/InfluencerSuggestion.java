package com.hapbang.auxiliary.search.influencer.app.dto;

/**
 * 인플루언서 검색어 자동완성 후보 하나(#973). 누르면 그 인플루언서로 바로 가거나({@code userId}), 닉네임으로 검색할 수 있게({@code nickname})
 * 둘 다 준다.
 *
 * @param userId   회원 ID
 * @param nickname 닉네임
 */
public record InfluencerSuggestion(long userId, String nickname) {
}
