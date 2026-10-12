package com.hapbang.member.in;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.hapbang.shared.member.domain.Role;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 로그인 회원 정보 (세션에 저장). getName()은 회원 ID */
@Getter
@RequiredArgsConstructor
public class MemberOAuth2User implements OAuth2User, Serializable {

    private final Long userId;
    private final Role role;
    private final Map<String, Object> attributes;

    /** 회원 ID (global의 @LoginUserId 리졸버가 이 값을 꺼낸다) */
    @Override
    public String getName() {
        return String.valueOf(userId);
    }

    /** 역할을 Spring Security 권한으로 (ROLE_ 접두사) */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
