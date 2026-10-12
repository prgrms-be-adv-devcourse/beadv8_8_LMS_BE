package com.hapbang.member.app;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import lombok.RequiredArgsConstructor;

/** 회원 조회 공통 (없으면 MEMBER_NOT_FOUND) */
@Service
@RequiredArgsConstructor
public class MemberSupport {

    private final UserRepository userRepository;

    public User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /** 정상(ACTIVE) 회원만. 탈퇴·정지 회원은 없는 회원처럼 MEMBER_NOT_FOUND */
    public User findActiveUser(Long userId) {
        User user = findUser(userId);
        if (!user.isActive()) {
            throw new MemberException(MemberErrorCode.MEMBER_NOT_FOUND);
        }
        return user;
    }

    /** 정상 인플루언서·인증된 인플루언서 목록 */
    public Page<User> findInfluencers(Pageable pageable) {
        return userRepository.findByRoleInAndStatus(List.of(Role.INFLUENCER, Role.VERIFIED_INFLUENCER),
                UserStatus.ACTIVE, pageable);
    }
}
