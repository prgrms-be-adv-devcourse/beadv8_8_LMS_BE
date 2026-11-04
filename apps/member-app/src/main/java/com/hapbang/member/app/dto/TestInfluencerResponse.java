package com.hapbang.member.app.dto;

import com.hapbang.shared.member.domain.Role;

/** changed=false면 이미 인플루언서인 테스트 회원이다. */
public record TestInfluencerResponse(Long userId, Role role, boolean changed) {
}
