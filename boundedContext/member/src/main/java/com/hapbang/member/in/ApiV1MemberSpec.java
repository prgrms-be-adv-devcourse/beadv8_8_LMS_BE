package com.hapbang.member.in;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;

import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 회원 API Swagger 명세. 매핑은 {@link ApiV1MemberController}. 성공 응답은 RsData의 data에 담는다 */
@Tag(name = "회원", description = "내 정보, 인플루언서 목록, 회원 프로필")
public interface ApiV1MemberSpec {

    @Operation(summary = "내 정보 조회",
            description = "로그인한 회원. 이메일을 포함한다. 프로필 이미지는 아직 항상 null.")
    RsData<MemberMeResponse> me(Long userId);

    @Operation(summary = "내 정보 수정",
            description = "로그인한 회원. 지금은 닉네임만. 안 보내거나 null이면 그대로 둔다. "
                    + "닉네임은 앞뒤 공백을 지운 뒤 2~12자, 한글·영문·숫자·공백·밑줄만, 연속 공백 불가 (어기면 400).")
    RsData<MemberMeResponse> updateMe(Long userId, MemberUpdateRequest request);

    @Operation(summary = "인플루언서 목록",
            description = "로그인한 회원. 인플루언서·인증된 인플루언서 중 정상 회원만, 최신 가입순. "
                    + "page·size로 페이징(최대 100). 정렬은 바꿀 수 없다.")
    RsData<PagedModel<MemberProfileResponse>> influencers(Pageable pageable);

    @Operation(summary = "회원 프로필",
            description = "로그인한 회원. 공개 정보(id·닉네임·역할·프로필 이미지)와 등록된 채널 전부 "
                    + "(채널 이름·주소·팔로워 수, 승인 상태는 넣지 않음). 탈퇴·없는 회원은 404.")
    RsData<MemberProfileDetailResponse> profile(Long memberId);
}
