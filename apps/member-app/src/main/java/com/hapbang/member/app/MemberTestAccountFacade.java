package com.hapbang.member.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.app.dto.TestInfluencerResponse;
import com.hapbang.member.domain.LoadTestAccount;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 부하 시험용 회원 승급. 2026-11-04 사용자 결정으로 인증 없이 공개한다.
 * 채널 인증 예외는 accounts.py가 생성하는 이메일 형식(배치당 001~100, {@link LoadTestAccount})에만 적용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberTestAccountFacade {

    private final MemberSupport memberSupport;

    @Transactional
    public TestInfluencerResponse promote(Long userId) {
        User user = memberSupport.findActiveUser(userId);
        if (!LoadTestAccount.isLoadTestEmail(user.getEmail())) {
            throw new MemberException(MemberErrorCode.TEST_ACCOUNT_REQUIRED);
        }
        if (user.getRole() == Role.INFLUENCER) {
            return new TestInfluencerResponse(user.getId(), user.getRole(), false);
        }
        if (user.getRole() != Role.USER) {
            throw new MemberException(MemberErrorCode.TEST_ACCOUNT_ROLE_NOT_ALLOWED);
        }
        memberSupport.changeRole(user, Role.INFLUENCER);
        log.info("테스트 회원 승급: userId={}, role={}", user.getId(), user.getRole());
        return new TestInfluencerResponse(user.getId(), user.getRole(), true);
    }
}
