package com.hapbang.member.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 계정 관리 진입점 (#517). /admin 경로는 SecurityConfig도 함께 막는다.
 * 첫 관리자는 초기 관리자 계정(#520)이고, 그 계정으로 로그인해 이 API로 관리자를 늘린다
 */
@Slf4j
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MemberAdminAccountFacade {

    private final MemberSignUpUseCase memberSignUpUseCase;

    /** 관리자 계정 생성. 이메일·비밀번호 로그인으로 들어온다 (가입과 같은 규칙, 역할만 관리자) */
    @Transactional
    public User createAdmin(Long adminId, String email, String password, String nickname) {
        User created = memberSignUpUseCase.registerAdmin(email, password, nickname);
        log.info("관리자 계정 생성: adminId={}, createdUserId={}", adminId, created.getId());
        return created;
    }
}
