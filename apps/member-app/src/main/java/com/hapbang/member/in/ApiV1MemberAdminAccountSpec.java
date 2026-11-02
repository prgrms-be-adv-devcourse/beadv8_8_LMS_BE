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
                    + "(이미 가입된 이메일 409 DUPLICATE_EMAIL, 비밀번호 규칙 400). "
                    + "운영 첫 관리자는 로컬 로그인의 관리자 테스트 계정으로 이 API를 불러 만든다.")
    RsData<MemberMeResponse> create(Long adminId, SignUpRequest request);
}
