package com.hapbang.shared.member.dto;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 다른 모듈에 넘기는 회원 정보 (복제 필드).
 * version은 회원 원본의 버전(@Version)이다. 복제본은 이미 반영한 버전보다 크지 않으면 무시한다 (이벤트 순서 뒤바뀜·재처리 대비).
 * null이면 버전을 모르는 정보라 비교 없이 반영한다 (테스트, 버전 도입 전에 저장된 이벤트)
 */
public record UserDto(
        Long id,
        String nickname,
        Role role,
        UserStatus status,
        Long version
) {

    /** 버전 없는 회원 정보 (비교 없이 반영된다) */
    public UserDto(Long id, String nickname, Role role, UserStatus status) {
        this(id, nickname, role, status, null);
    }
}
