package com.hapbang.auxiliary.search.influencer.out;

import java.util.List;

/**
 * 회원 이벤트로 받는 인플루언서 프로필 필드(#771). 문서에 그대로 쓴다. 통계(채널·팔로워·평점)는 여기 없다(10분 동기화가 쓴다).
 *
 * @param nickname          닉네임
 * @param bio               자기소개. 없으면 null
 * @param role              역할(INFLUENCER·VERIFIED_INFLUENCER)
 * @param verified          인증된 인플루언서인지
 * @param contentCategories 콘텐츠 분야. 회원이 고른 순서이고, 이름을 모르면 name이 null
 */
public record MemberProfile(String nickname, String bio, String role, boolean verified,
                            List<Category> contentCategories) {

    public MemberProfile {
        contentCategories = List.copyOf(contentCategories);
    }

    /** 문서의 콘텐츠 분야 한 칸 */
    public record Category(String code, String name) {
    }
}
