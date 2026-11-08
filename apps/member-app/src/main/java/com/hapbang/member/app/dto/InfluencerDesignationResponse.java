package com.hapbang.member.app.dto;

import com.hapbang.shared.member.domain.Role;

/** 관리자 인플루언서 지정 결과. changed=false면 이미 인플루언서인 회원이다. */
public record InfluencerDesignationResponse(Long userId, Role role, boolean changed) {
}
