package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import com.hapbang.shared.member.domain.Role;

class MemberOAuth2UserTest {

    @Test
    @DisplayName("getName()은 회원 ID를 문자열로 반환한다")
    void getName() {
        MemberOAuth2User user = new MemberOAuth2User(1L, Role.USER, Map.of());

        assertThat(user.getName()).isEqualTo("1");
    }

    @Test
    @DisplayName("권한은 ROLE_ 접두사를 붙인 역할이다")
    void getAuthorities() {
        MemberOAuth2User user = new MemberOAuth2User(1L, Role.INFLUENCER, Map.of());

        assertThat(user.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_INFLUENCER");
    }
}
