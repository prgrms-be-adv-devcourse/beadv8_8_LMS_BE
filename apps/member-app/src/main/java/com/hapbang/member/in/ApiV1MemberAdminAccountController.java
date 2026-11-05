package com.hapbang.member.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.app.MemberAdminAccountFacade;
import com.hapbang.member.domain.User;
import com.hapbang.member.global.auth.LoginUserId;
import com.hapbang.member.global.response.RsData;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 관리자 계정 API (#517) */
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 MemberAdminAccountFacade의 @PreAuthorize로 함께 막는다
@RequestMapping("/api/v1/members/admin/accounts")
@RequiredArgsConstructor
public class ApiV1MemberAdminAccountController implements ApiV1MemberAdminAccountSpec {

    private final MemberAdminAccountFacade memberAdminAccountFacade;

    /** 관리자 계정 생성 */
    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<MemberMeResponse> create(@LoginUserId Long adminId,
                                           @Valid @RequestBody AdminAccountCreateRequest request) {
        User created = memberAdminAccountFacade.createAdmin(adminId, request.email(), request.password(),
                request.nickname());
        // 새 계정이라 프로필 사진이 없다
        return RsData.created("관리자 계정을 만들었습니다.", MemberMeResponse.from(created, null));
    }
}
