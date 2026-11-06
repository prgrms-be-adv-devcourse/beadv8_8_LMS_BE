package com.hapbang.member.in;

import com.hapbang.member.app.dto.TestInfluencerResponse;
import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "테스트 회원", description = "관리자의 인플루언서 승급 (부하 시험·테스트용)")
public interface ApiV1MemberTestAccountSpec {

    @Operation(summary = "일반 회원을 인플루언서로 승급",
            description = "ADMIN 권한으로만 호출한다. 비로그인 401, ADMIN이 아니면 403이다. "
                    + "ACTIVE 회원이면 이메일과 상관없이 대상이며, 채널 인증 없이 USER를 INFLUENCER로 바꾸고 회원 변경 이벤트를 발행한다. "
                    + "이미 INFLUENCER면 changed=false로 200이며 이벤트를 다시 발행하지 않는다. "
                    + "다른 역할은 409 TEST_ACCOUNT_ROLE_NOT_ALLOWED, "
                    + "없는 회원·비활성 회원은 404 MEMBER_NOT_FOUND. 토큰 재발급과 소비 앱의 이벤트 반영 확인이 필요하다.")
    RsData<TestInfluencerResponse> promote(Long adminId, Long userId);
}
