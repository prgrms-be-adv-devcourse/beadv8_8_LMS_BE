package com.hapbang.auxiliary.chatbot.domain;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 인플루언서 추천이 보는 회원 사본 한 명 (#719).
 * <p>
 * <b>추천 가능 = 인플루언서 역할({@link #isInfluencer()}) ∧ {@code ACTIVE} ∧ 마스킹 안 됨.</b> 색인 문서의 {@code hidden=false}와 같은 규칙이다.
 * 같은 규칙을 SQL로도 쓴다({@code InfluencerRecommendStore.RECOMMENDABLE}, 한 번에 많은 회원을 거를 때). 둘을 함께 고친다.
 * 정지를 빼는 것은 추천 후보 조건이다({@code MemberVisibility}는 탈퇴만 숨긴다).
 *
 * @param categories 분야 코드(회원이 고른 순서)
 * @param bio        자기소개(없으면 null). 임베딩 API로만 보낸다(LLM 프롬프트·로그에 넣지 않는다)
 * @param masked     개인정보를 마스킹한 회원
 */
public record InfluencerProfile(
        long id,
        Role role,
        UserStatus status,
        List<String> categories,
        @Nullable String bio,
        boolean masked
) {

    public InfluencerProfile {
        categories = List.copyOf(categories);
    }

    /** 인플루언서 역할(인증 여부 무관) */
    public boolean isInfluencer() {
        return role == Role.INFLUENCER || role == Role.VERIFIED_INFLUENCER;
    }

    /** 추천 가능(색인 문서 {@code hidden=false}) */
    public boolean recommendable() {
        return !masked && status == UserStatus.ACTIVE && isInfluencer();
    }
}
