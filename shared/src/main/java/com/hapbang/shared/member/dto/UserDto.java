package com.hapbang.shared.member.dto;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 다른 모듈에 넘기는 회원 정보 (복제 필드).
 * version은 회원 원본의 버전(@Version)이다. 복제본은 이미 반영한 버전보다 크지 않으면 무시한다 (이벤트 순서 뒤바뀜·재처리 대비).
 * null이면 버전을 모르는 정보다: 아직 버전을 기록하지 않은 복제본에는 반영하고,
 * 이미 버전을 기록한 복제본은 버전 도입 전에 저장된 옛 이벤트로 보고 무시한다
 */
public record UserDto(
        Long id,
        String nickname,
        Role role,
        UserStatus status,
        Long version
) {

    /** 버전 없는 회원 정보 (테스트용. 버전을 기록한 복제본은 무시한다) */
    public UserDto(Long id, String nickname, Role role, UserStatus status) {
        this(id, nickname, role, status, null);
    }
}
