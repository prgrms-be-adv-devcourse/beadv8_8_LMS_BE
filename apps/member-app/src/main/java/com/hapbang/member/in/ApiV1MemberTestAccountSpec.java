package com.hapbang.member.in;

import com.hapbang.member.app.dto.TestInfluencerResponse;
import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "테스트 회원", description = "부하 시험 전용 회원 준비")
public interface ApiV1MemberTestAccountSpec {

    @SecurityRequirements
    @Operation(summary = "테스트 일반 회원을 인플루언서로 승급",
            description = "인증·관리자 권한 없이 호출한다(2026-11-04 사용자 결정). "
                    + "accounts.py의 k6-날짜-시각-6자리 소문자 hex-001~100@example.invalid 형식인 ACTIVE 회원만 대상이다. "
                    + "USER를 INFLUENCER로 바꾸고 회원 변경 이벤트를 발행한다. "
                    + "이미 INFLUENCER면 changed=false로 200이며 이벤트를 다시 발행하지 않는다. "
                    + "일반 계정은 403 TEST_ACCOUNT_REQUIRED, 다른 역할은 409 TEST_ACCOUNT_ROLE_NOT_ALLOWED, "
                    + "없는 회원·비활성 회원은 404 MEMBER_NOT_FOUND. 토큰 재발급과 소비 앱의 이벤트 반영 확인이 필요하다.")
    RsData<TestInfluencerResponse> promote(Long userId);
}
