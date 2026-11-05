package com.hapbang.member.in;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.app.MemberTestAccountFacade;
import com.hapbang.member.app.dto.TestInfluencerResponse;
import com.hapbang.member.global.response.RsData;
import com.hapbang.member.global.auth.LoginUserId;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/members/test")
@RequiredArgsConstructor
public class ApiV1MemberTestAccountController implements ApiV1MemberTestAccountSpec {

    private final MemberTestAccountFacade memberTestAccountFacade;

    @Override
    @PostMapping("/{userId}/influencer")
    public RsData<TestInfluencerResponse> promote(@LoginUserId Long adminId, @PathVariable("userId") Long userId) {
        return RsData.ok("테스트 회원의 인플루언서 역할을 확인했습니다.", memberTestAccountFacade.promote(adminId, userId));
    }
}
