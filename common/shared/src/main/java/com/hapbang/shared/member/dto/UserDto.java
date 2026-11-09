package com.hapbang.shared.member.dto;

import java.util.List;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 다른 모듈에 넘기는 회원 정보 (복제 필드).
 * version은 회원 원본의 버전(@Version)이다. 복제본은 이미 반영한 버전보다 크지 않으면 무시한다 (이벤트 순서 뒤바뀜·재처리 대비).
 * null이면 버전을 모르는 정보다: 아직 버전을 기록하지 않은 복제본에는 반영하고,
 * 이미 버전을 기록한 복제본은 버전 도입 전에 저장된 옛 이벤트로 보고 무시한다.
 * <p>
 * contentCategories는 콘텐츠 분야 코드 목록이다(#711). 회원이 고른 순서이고, 없으면 빈 목록이다(null 아님).
 * 코드는 enum이 아니라 문자열이라 처음 보는 코드가 와도 읽기가 실패하지 않는다. 코드의 이름은 회원 앱의 분야 목록 API로 얻는다.
 * bio는 자기소개이고 없으면 null이다. 회원 앱은 모든 UserDto에 그 시점의 두 값을 싣는다.
 * 빈 목록·null이 오면 받는 쪽은 지운 것으로 처리한다
 */
public record UserDto(
        Long id,
        String nickname,
        Role role,
        UserStatus status,
        Long version,
        List<String> contentCategories,
        String bio
) {

    /** 분야가 없으면(옛 JSON·옛 생성자) 빈 목록으로 두고, 받은 목록을 복사해 바깥에서 바꿔도 영향이 없게 한다. null 코드는 받지 않는다 */
    public UserDto {
        contentCategories = contentCategories == null ? List.of() : List.copyOf(contentCategories);
    }

    /** 분야·자기소개 없는 회원 정보 (#711 전 호출부용. 분야는 빈 목록, 자기소개는 null) */
    public UserDto(Long id, String nickname, Role role, UserStatus status, Long version) {
        this(id, nickname, role, status, version, List.of(), null);
    }

    /** 버전 없는 회원 정보 (테스트용. 버전을 기록한 복제본은 무시한다) */
    public UserDto(Long id, String nickname, Role role, UserStatus status) {
        this(id, nickname, role, status, null);
    }
}
