package com.hapbang.member.in;

import java.io.IOException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/** 회원 API Swagger 명세. 매핑은 {@link ApiV1MemberController}. 성공 응답은 RsData의 data에 담는다 */
@Tag(name = "회원", description = "내 정보, 인플루언서 목록, 회원 프로필")
public interface ApiV1MemberSpec {

    @Operation(summary = "내 정보 조회",
            description = "로그인한 회원. 이메일을 포함한다. profileImageUrl은 1시간 동안 쓸 수 있는 조회 주소, 사진이 없으면 null.")
    RsData<MemberMeResponse> me(Long userId);

    @Operation(summary = "내 정보 수정",
            description = "로그인한 회원. 지금은 닉네임만. 안 보내거나 null이면 그대로 둔다. "
                    + "닉네임은 앞뒤 공백을 지운 뒤 2~12자, 한글·영문·숫자·공백·밑줄만, 연속 공백 불가 (어기면 400).")
    RsData<MemberMeResponse> updateMe(Long userId, MemberUpdateRequest request);

    @Operation(summary = "인플루언서 목록",
            description = "로그인한 회원. 인플루언서·인증된 인플루언서 중 정상 회원만, 최신 가입순. "
                    + "page·size로 페이징(최대 100). 정렬은 바꿀 수 없다.")
    RsData<Page<MemberProfileResponse>> influencers(Pageable pageable);

    @Operation(summary = "회원 프로필",
            description = "로그인한 회원. 공개 정보(id·닉네임·역할·프로필 이미지)와 등록된 채널 전부 "
                    + "(채널 이름·주소·팔로워 수, 승인 상태는 넣지 않음). 탈퇴·없는 회원은 404.")
    RsData<MemberProfileDetailResponse> profile(Long memberId);

    @Operation(summary = "프로필 사진 올리기",
            description = "로그인한 회원 본인. 요청 본문이 이미지 그대로다 (multipart 아님). "
                    + "Content-Type은 image/jpeg·image/png·image/webp, Content-Length 필수(최대 5MB). "
                    + "{memberId}에는 내 회원 ID를 넣는다 (팀 업로드 경로 규칙 PUT /files/{id}/content, 프로필 사진은 회원당 하나). "
                    + "남의 ID면 403 PROFILE_IMAGE_FORBIDDEN, 형식·크기 위반 400, "
                    + "파일 내용(앞부분 바이트)이 Content-Type 형식과 다르면 400 PROFILE_IMAGE_CONTENT_MISMATCH. 올릴 때마다 새 파일로 저장하고 바로 프로필로 지정한다. "
                    + "Swagger에서는 형식을 고르고 파일을 선택해 보낸다.",
            requestBody = @RequestBody(required = true, content = {
                    @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/webp", schema = @Schema(type = "string", format = "binary"))
            }))
    RsData<MemberMeResponse> uploadProfileImage(Long userId, Long memberId, HttpServletRequest request) throws IOException;

    @Operation(summary = "프로필 사진 삭제",
            description = "로그인한 회원 본인. 프로필 사진 연결만 해제한다 (S3 파일은 지우지 않는다). 사진이 없어도 200.")
    RsData<MemberMeResponse> deleteProfileImage(Long userId);
}
