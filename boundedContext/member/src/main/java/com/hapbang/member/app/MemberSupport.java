package com.hapbang.member.app;

import org.springframework.stereotype.Service;

import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;

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
}
