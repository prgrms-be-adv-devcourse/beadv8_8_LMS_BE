package com.hapbang.member.in;

import java.io.IOException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.global.auth.LoginUserId;
import com.hapbang.member.global.response.RsData;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.User;

import jakarta.servlet.http.HttpServletRequest;

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
        return RsData.ok("내 정보를 조회했습니다.", meResponse(memberFacade.findById(userId)));
    }

    /** 내 정보 수정 (닉네임) */
    @Override
    @PatchMapping("/me")
    public RsData<MemberMeResponse> updateMe(@LoginUserId Long userId, @RequestBody MemberUpdateRequest request) {
        return RsData.ok("내 정보를 수정했습니다.", meResponse(memberFacade.updateProfile(userId, request.nickname())));
    }

    /** 인플루언서 목록 (최신 가입순) */
    @Override
    @GetMapping("/influencers")
    public RsData<Page<MemberProfileResponse>> influencers(Pageable pageable) {
        return RsData.ok("인플루언서 목록을 조회했습니다.",
                memberFacade.findInfluencers(pageable).map(user ->
                        MemberProfileResponse.from(user, memberFacade.profileImageUrl(user.getProfileImageKey()))));
    }

    /** 회원 프로필 (등록된 채널 포함). 숫자만 받아 /local-login 같은 다른 주소와 겹치지 않게 한다 */
    @Override
    @GetMapping("/{memberId:\\d+}")
    public RsData<MemberProfileDetailResponse> profile(@PathVariable(name = "memberId") Long memberId) {
        User user = memberFacade.findProfile(memberId);
        return RsData.ok("회원 프로필을 조회했습니다.", MemberProfileDetailResponse.from(user,
                memberFacade.profileImageUrl(user.getProfileImageKey()), memberFacade.findChannels(memberId)));
    }

    /** 프로필 사진 올리기. 본문이 이미지 그대로 (multipart 아님). 팀 업로드 경로 규칙이라 {memberId}에 내 회원 ID */
    @Override
    @PutMapping("/files/{memberId:\\d+}/content")
    public RsData<MemberMeResponse> uploadProfileImage(@LoginUserId Long userId,
                                                       @PathVariable(name = "memberId") Long memberId,
                                                       HttpServletRequest request) throws IOException {
        // 본문은 메모리에 모으지 않고 스트림 그대로 S3로 넘긴다 (Content-Length가 없으면 -1 → 크기 오류)
        User user = memberFacade.uploadProfileImage(userId, memberId, request.getContentType(),
                request.getContentLengthLong(), request.getInputStream());
        return RsData.ok("프로필 사진을 바꿨습니다.", meResponse(user));
    }

    /** 프로필 사진 삭제 (DB에서만 연결 해제, S3 파일은 그대로) */
    @Override
    @DeleteMapping("/me/profile-image")
    public RsData<MemberMeResponse> deleteProfileImage(@LoginUserId Long userId) {
        return RsData.ok("프로필 사진을 삭제했습니다.", meResponse(memberFacade.deleteProfileImage(userId)));
    }

    private MemberMeResponse meResponse(User user) {
        return MemberMeResponse.from(user, memberFacade.profileImageUrl(user.getProfileImageKey()));
    }
}
