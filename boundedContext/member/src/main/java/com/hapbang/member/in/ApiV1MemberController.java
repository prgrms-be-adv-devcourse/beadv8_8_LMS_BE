package com.hapbang.member.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.member.app.MemberFacade;

import lombok.RequiredArgsConstructor;

/** 회원 API */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class ApiV1MemberController {

    private final MemberFacade memberFacade;

    /** 내 정보 조회 */
    @GetMapping("/me")
    public MemberMeResponse me(@LoginUserId Long userId) {
        return MemberMeResponse.from(memberFacade.findById(userId));
    }

    /** 내 정보 수정 (닉네임) */
    @PatchMapping("/me")
    public MemberMeResponse updateMe(@LoginUserId Long userId, @RequestBody MemberUpdateRequest request) {
        return MemberMeResponse.from(memberFacade.updateProfile(userId, request.nickname()));
    }
}
