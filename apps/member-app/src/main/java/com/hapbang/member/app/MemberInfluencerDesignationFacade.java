package com.hapbang.member.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.app.dto.InfluencerDesignationResponse;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 인플루언서 지정: 관리자가 유저를 채널 인증 없이 인플루언서로 바꾼다 (부하 시험·테스트용). 2026-11-04 변경 결정으로 ADMIN만 호출한다.
 * 2026-11-06 사용자 결정(#724)으로 부하 시험 이메일 형식 제한을 없애 ACTIVE USER 누구나 대상이다.
 */
@Slf4j
@Service
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MemberInfluencerDesignationFacade {

    private final MemberSupport memberSupport;

    @Transactional
    public InfluencerDesignationResponse makeInfluencer(Long adminId, Long userId) {
        User user = memberSupport.findActiveUser(userId);
        if (user.getRole() == Role.INFLUENCER) {
            return new InfluencerDesignationResponse(user.getId(), user.getRole(), false);
        }
        if (user.getRole() != Role.USER) {
            throw new MemberException(MemberErrorCode.INFLUENCER_DESIGNATION_ROLE_NOT_ALLOWED);
        }
        memberSupport.changeRole(user, Role.INFLUENCER);
        log.info("관리자가 회원을 인플루언서로 지정했다 adminId={}, userId={}, role={}", adminId, user.getId(), user.getRole());
        return new InfluencerDesignationResponse(user.getId(), user.getRole(), true);
    }
}
