package com.hapbang.member.in;

import java.io.IOException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 회원 API Swagger 명세. 매핑은 {@link ApiV1MemberController}. 성공 응답은 RsData의 data에 담는다 */
@Tag(name = "회원", description = "내 정보, 인플루언서 목록, 회원 프로필")
public interface ApiV1MemberSpec {

    @Operation(summary = "내 정보 조회",
            description = "로그인한 회원. 이메일을 포함한다. profileImageUrl은 1시간 동안 쓸 수 있는 조회 주소, 사진이 없으면 null.")
    RsData<MemberMeResponse> me(Long userId);

    @Operation(summary = "내 정보 수정",
            description = "로그인한 회원. 닉네임·콘텐츠 분야(contentCategories)·자기소개(bio). 안 보내거나 null이면 그대로 둔다. "
                    + "닉네임은 앞뒤 공백을 지운 뒤 2~12자, 한글·영문·숫자·공백·밑줄만, 연속 공백 불가 (어기면 400). "
                    + "다른 회원(탈퇴 회원 포함)이 쓰는 닉네임이면 대소문자만 달라도 409 DUPLICATE_NICKNAME. 내 닉네임의 대소문자만 바꾸는 것은 된다. "
                    + "contentCategories는 분야 코드 목록(고른 순서 유지, 예: [\"MAPLESTORY\", \"GAME\"]), 0~5개·중복 불가. "
                    + "6개 이상이면 400 INVALID_CONTENT_CATEGORY_COUNT, 중복이면 400 DUPLICATE_CONTENT_CATEGORY, "
                    + "새로 고르는 코드가 분야 목록에 없거나 꺼져 있으면 400 INVALID_CONTENT_CATEGORY(이미 고른 코드는 꺼졌어도 다시 보낼 수 있다). "
                    + "bio는 앞뒤 공백을 지운 뒤 100자 이하(넘으면 400 INVALID_BIO_LENGTH). "
                    + "빈 목록·빈 문자열을 보내면 지운다. 비어 있지 않은 분야·자기소개는 인플루언서·인증된 인플루언서만 보낼 수 있고 "
                    + "그 밖의 역할이면 요청 전체가 403 INFLUENCER_PROFILE_NOT_ALLOWED(아무것도 바뀌지 않음). 지우기는 누구나 된다. "
                    + "무엇이든 바뀌면 회원 변경 이벤트가 한 번 나가고, 바뀐 것이 없으면 나가지 않는다.")
    RsData<MemberMeResponse> updateMe(Long userId, MemberUpdateRequest request);

    @Operation(summary = "닉네임 중복 확인",
            description = "로그인 없이 부른다 (#646). 가입·내 정보 수정 전에 닉네임을 쓸 수 있는지 알려 준다(available). "
                    + "대소문자를 무시하고(Tester = tester), 탈퇴 회원 닉네임도 쓸 수 없다. 로그인하고 부르면 내 닉네임은 true다. "
                    + "규칙에 맞지 않으면 400 INVALID_NICKNAME_LENGTH·INVALID_NICKNAME_CHARACTER·INVALID_NICKNAME_SPACE. "
                    + "확인한 뒤 다른 사람이 먼저 가져가면 가입·수정에서 409 DUPLICATE_NICKNAME이 날 수 있다.")
    RsData<NicknameAvailabilityResponse> nicknameAvailability(Long userId,
            @Parameter(description = "확인할 닉네임 (앞뒤 공백은 지우고 본다)", example = "홍길동") String nickname);

    @Operation(summary = "인플루언서 목록",
            description = "로그인한 회원. 인플루언서·인증된 인플루언서 중 정상 회원만, 최신 가입순. "
                    + "page·size로 페이징(최대 100). 정렬은 바꿀 수 없다. "
                    + "appearanceRating은 받은 출연 리뷰 전체 평점 {average, count} "
                    + "(average는 소수 첫째 자리 반올림, 리뷰가 없으면 average null·count 0).")
    RsData<Page<MemberProfileResponse>> influencers(Pageable pageable);

    @Operation(summary = "회원 프로필",
            description = "로그인한 회원. 공개 정보(id·닉네임·역할·프로필 이미지)와 등록된 채널 전부 "
                    + "(채널 이름·주소·팔로워 수, 승인 상태는 넣지 않음). 탈퇴·없는 회원은 404. "
                    + "appearanceRating은 출연 평점 {total, asHost, asGuest}: 전체, 호스트로 받은 리뷰(게스트가 씀), "
                    + "게스트로 받은 리뷰(호스트가 씀). 각각 {average, count}이고 average는 소수 첫째 자리 반올림, "
                    + "리뷰가 없으면 average null·count 0.")
    RsData<MemberProfileDetailResponse> profile(Long memberId);

    @Operation(summary = "받은 출연 리뷰 목록",
            description = "로그인한 회원. 그 회원이 받은 공개된 출연 리뷰를 최신 공개순으로 준다(정렬은 바꿀 수 없다). "
                    + "page·size로 페이징(최대 100). revieweeRole은 받은 회원이 그 출연에서 맡은 역할(HOST·GUEST). "
                    + "reviewer는 쓴 회원 {id, nickname, profileImageUrl}이고, 탈퇴한 회원이면 nickname은 '탈퇴한 회원', 사진은 null. "
                    + "프로필과 같이 탈퇴·없는 회원은 404. 출연 앱을 부를 수 없으면 503 APPEARANCE_REVIEW_UNAVAILABLE.")
    RsData<Page<AppearanceReviewResponse>> appearanceReviews(Long memberId, Pageable pageable);

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
            description = "로그인한 회원 본인. 프로필 사진 연결을 해제하고 S3 파일은 빈 파일로 덮어 지운다(#594). 사진이 없어도 200.")
    RsData<MemberMeResponse> deleteProfileImage(Long userId);

    @Operation(summary = "탈퇴 회원 복구",
            description = "로그인하지 않고 부른다 (#593). 탈퇴하고 30일 안에 로그인하면 409 WITHDRAWN_RESTORABLE(비밀번호 로그인) 또는 "
                    + "/login?error=WITHDRAWN_RESTORABLE&restorableUntil=…(구글 로그인)을 받고, 두 경우 모두 복구 티켓(10분)은 "
                    + "쿠키 RESTORE_TICKET(HttpOnly, 경로 /api/v1/members/restore)로 받는다. 본문 없이 부르면 브라우저가 쿠키를 붙여 보낸다 "
                    + "(주소·본문에 티켓을 싣지 않는다, #633). 탈퇴 때 닫은 지갑을 다시 열고 계정을 되살린 뒤 204를 주고 복구 티켓 쿠키를 지운다. "
                    + "로그인은 하지 않는다: 프론트는 204를 받으면 로그인을 다시 부른다(비밀번호 로그인 다시 호출 또는 구글 로그인으로 다시 이동). "
                    + "복구 티켓만으로 로그인까지 되면 공용 PC에 남은 티켓으로 계정을 빼앗을 수 있어서다. "
                    + "쿠키가 없거나 만료되면 401 INVALID_RESTORE_TICKET(다시 로그인), 복구 기한이 지났으면 403 LOGIN_NOT_ALLOWED "
                    + "(두 경우 쿠키를 지운다), 지갑을 열지 못하면 503 RESTORE_UNAVAILABLE(복구하지 않음, 쿠키는 남아 그대로 다시 시도). "
                    + "기한 안에 503으로 실패했으면 복구 기한이 7일 늘어난다(#594, 늘어난 기한은 다음 로그인의 restorableUntil에 나온다). "
                    + "기한이 지나면 개인정보를 파기해 복구할 수 없다.")
    ResponseEntity<Void> restore(
            @Parameter(in = ParameterIn.COOKIE, name = RestoreTicketCookies.NAME, description = "로그인 때 받은 복구 티켓")
            String restoreTicket,
            @Parameter(hidden = true) HttpServletResponse response);

    @Operation(summary = "탈퇴 회원 복구 취소",
            description = "로그인하지 않고 부른다 (#633). \"복구할까요?\"에서 복구하지 않으면 부른다. 복구 티켓 쿠키(RESTORE_TICKET)의 "
                    + "티켓을 지우고 쿠키도 지워, 공용 PC에서 다음 사람이 그 티켓으로 원치 않는 복구를 하지 못하게 한다. "
                    + "쿠키가 없거나 티켓이 이미 없어도 204.")
    ResponseEntity<Void> cancelRestore(
            @Parameter(in = ParameterIn.COOKIE, name = RestoreTicketCookies.NAME, description = "로그인 때 받은 복구 티켓")
            String restoreTicket);

    @Operation(summary = "회원 탈퇴",
            description = "로그인한 회원 본인 (#593). 지갑·출연·강의에 탈퇴해도 되는지 확인한 뒤 탈퇴한다. "
                    + "성공하면 204이고 refresh token 쿠키를 지운다(그 회원의 refresh token은 서버에서도 모두 폐기). "
                    + "이미 탈퇴했으면 그대로 204. 남은 돈·진행 중 거래가 있으면 409 WITHDRAWAL_BLOCKED + reasons"
                    + "[{code, message, availableAt?}](모든 앱의 이유를 한 번에, 회원이 정리한 뒤 다시 탈퇴), "
                    + "이미 처리 중이면 409 WITHDRAWAL_IN_PROGRESS, 다른 앱을 부를 수 없으면 503 WITHDRAWAL_UNAVAILABLE. "
                    + "탈퇴 기능이 꺼져 있으면 404. 30일 안에 다시 로그인하면 복구할 수 있고, 30일이 지나면 개인정보를 파기한다(#594). "
                    + "파기한 뒤에는 같은 이메일·구글 계정으로 새로 가입할 수 있다.")
    ResponseEntity<Void> withdraw(Long userId);
}
