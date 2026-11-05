package com.hapbang.member.in;

import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 관리자 계정 API Swagger 명세 (#517). 매핑은 {@link ApiV1MemberAdminAccountController} */
@Tag(name = "관리자 계정", description = "관리자가 관리자 계정을 만든다")
public interface ApiV1MemberAdminAccountSpec {

    @Operation(summary = "관리자 계정 생성",
            description = "관리자만 (아니면 403). 이메일·비밀번호·닉네임으로 역할이 관리자인 새 계정을 만든다. "
                    + "만든 계정은 비밀번호 로그인(POST /api/v1/members/login)으로 들어온다. "
                    + "이메일·비밀번호·닉네임 규칙과 오류는 가입(POST /api/v1/members/signup)과 같다 "
                    + "(이미 가입된 이메일 409 DUPLICATE_EMAIL, 쓰고 있는 닉네임 409 DUPLICATE_NICKNAME, 비밀번호 규칙 400). "
                    + "가입과 달리 이메일 인증(#557)은 받지 않는다. "
                    + "첫 관리자는 앱이 시작할 때 만드는 초기 관리자 계정(admin@hapbang.local)이다.")
    RsData<MemberMeResponse> create(Long adminId, AdminAccountCreateRequest request);
}
