package com.hapbang.member.in;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.global.auth.LoginUserId;
import com.hapbang.global.response.RsData;
import com.hapbang.member.app.MemberFacade;

import lombok.RequiredArgsConstructor;

/** 회원 API */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class ApiV1MemberController implements ApiV1MemberSpec {

    private final MemberFacade memberFacade;

    /** 내 정보 조회 */
    @Override
    @GetMapping("/me")
    public RsData<MemberMeResponse> me(@LoginUserId Long userId) {
        return RsData.ok("내 정보를 조회했습니다.", MemberMeResponse.from(memberFacade.findById(userId)));
    }

    /** 내 정보 수정 (닉네임) */
    @Override
    @PatchMapping("/me")
    public RsData<MemberMeResponse> updateMe(@LoginUserId Long userId, @RequestBody MemberUpdateRequest request) {
        return RsData.ok("내 정보를 수정했습니다.",
                MemberMeResponse.from(memberFacade.updateProfile(userId, request.nickname())));
    }

    /** 인플루언서 목록 (최신 가입순) */
    @Override
    @GetMapping("/influencers")
    public RsData<PagedModel<MemberProfileResponse>> influencers(Pageable pageable) {
        // RsData 안에서도 팀 표준 페이지 형태({content, page})가 되도록 PagedModel로 직접 감싼다
        return RsData.ok("인플루언서 목록을 조회했습니다.",
                new PagedModel<>(memberFacade.findInfluencers(pageable).map(MemberProfileResponse::from)));
    }

    /** 회원 프로필 (등록된 채널 포함). 숫자만 받아 /local-login 같은 다른 주소와 겹치지 않게 한다 */
    @Override
    @GetMapping("/{memberId:\\d+}")
    public RsData<MemberProfileDetailResponse> profile(@PathVariable(name = "memberId") Long memberId) {
        return RsData.ok("회원 프로필을 조회했습니다.",
                MemberProfileDetailResponse.from(memberFacade.findProfile(memberId), memberFacade.findChannels(memberId)));
    }
}
