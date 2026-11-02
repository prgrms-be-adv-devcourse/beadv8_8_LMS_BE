package com.hapbang.member.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 해시 (#499). Spring Security 기본 위임 인코더로 지금은 BCrypt로 저장한다.
 * 해시 앞에 알고리즘 표시({bcrypt})가 붙어, 나중에 알고리즘을 바꿔도 예전 해시를 그대로 검사할 수 있다
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
