package com.hapbang.member.in;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.app.MemberInfluencerDesignationFacade;
import com.hapbang.member.app.dto.InfluencerDesignationResponse;
import com.hapbang.member.global.response.RsData;
import com.hapbang.member.global.auth.LoginUserId;

import lombok.RequiredArgsConstructor;

/** 관리자 인플루언서 지정 API (#724, #735) */
@RestController
// 관리자 API는 /admin 경로(SecurityConfig)와 MemberInfluencerDesignationFacade의 @PreAuthorize로 함께 막는다
@RequestMapping("/api/v1/members/admin/users")
@RequiredArgsConstructor
public class ApiV1MemberInfluencerDesignationController implements ApiV1MemberInfluencerDesignationSpec {

    private final MemberInfluencerDesignationFacade memberInfluencerDesignationFacade;

    @Override
    @PostMapping("/{userId}/make-influencer")
    public RsData<InfluencerDesignationResponse> makeInfluencer(@LoginUserId Long adminId,
            @PathVariable("userId") Long userId) {
        return RsData.ok("회원의 인플루언서 역할을 확인했습니다.",
                memberInfluencerDesignationFacade.makeInfluencer(adminId, userId));
    }
}
