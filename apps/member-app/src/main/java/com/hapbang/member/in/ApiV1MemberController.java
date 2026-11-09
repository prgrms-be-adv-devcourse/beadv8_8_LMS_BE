package com.hapbang.member.in;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.global.auth.LoginUserId;
import com.hapbang.member.global.response.RsData;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

/** 회원 API */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class ApiV1MemberController implements ApiV1MemberSpec {

    private final MemberFacade memberFacade;
    private final RefreshTokenCookies refreshTokenCookies;
    private final RestoreTicketCookies restoreTicketCookies;

    /** 내 정보 조회 */
    @Override
    @GetMapping("/me")
    public RsData<MemberMeResponse> me(@LoginUserId Long userId) {
        return RsData.ok("내 정보를 조회했습니다.", meResponse(memberFacade.findById(userId)));
    }

    /** 내 정보 수정 (닉네임·콘텐츠 분야·자기소개) */
    @Override
    @PatchMapping("/me")
    public RsData<MemberMeResponse> updateMe(@LoginUserId Long userId, @RequestBody MemberUpdateRequest request) {
        return RsData.ok("내 정보를 수정했습니다.", meResponse(
                memberFacade.updateProfile(userId, request.nickname(), request.contentCategories(), request.bio())));
    }

    /** 닉네임 중복 확인 (#646). 로그인 없이 부른다. 로그인했으면 자기 닉네임은 쓸 수 있다고 답한다 */
    @Override
    @GetMapping("/nicknames/availability")
    public RsData<NicknameAvailabilityResponse> nicknameAvailability(@LoginUserId(required = false) Long userId,
                                                                     @RequestParam(required = false) String nickname) {
        return RsData.ok("닉네임을 확인했습니다.",
                new NicknameAvailabilityResponse(memberFacade.isNicknameAvailable(nickname, userId)));
    }

    /** 인플루언서 목록 (최신 가입순). 출연 평점은 한 페이지의 회원을 한 번에 읽어 붙인다 */
    @Override
    @GetMapping("/influencers")
    public RsData<Page<MemberProfileResponse>> influencers(Pageable pageable) {
        Page<User> influencers = memberFacade.findInfluencers(pageable);
        Map<Long, AppearanceRatingSummary> ratings = memberFacade.findAppearanceRatings(
                influencers.map(User::getId).getContent());
        Map<String, String> categoryNames = memberFacade.findContentCategoryNames(influencers.stream()
                .flatMap(user -> user.getContentCategories().stream()).collect(Collectors.toSet()));
        return RsData.ok("인플루언서 목록을 조회했습니다.", influencers.map(user -> MemberProfileResponse.from(user,
                memberFacade.profileImageUrl(user.getProfileImageKey()),
                ratings.getOrDefault(user.getId(), AppearanceRatingSummary.EMPTY), categoryNames)));
    }

    /** 회원 프로필 (등록된 채널, 출연 평점 포함). 숫자만 받아 /signup 같은 다른 주소와 겹치지 않게 한다 */
    @Override
    @GetMapping("/{memberId:\\d+}")
    public RsData<MemberProfileDetailResponse> profile(@PathVariable(name = "memberId") Long memberId) {
        User user = memberFacade.findProfile(memberId);
        return RsData.ok("회원 프로필을 조회했습니다.", MemberProfileDetailResponse.from(user,
                memberFacade.profileImageUrl(user.getProfileImageKey()), memberFacade.findChannels(memberId),
                memberFacade.findAppearanceRating(memberId),
                memberFacade.findContentCategoryNames(user.getContentCategories())));
    }

    /** 회원이 받은 출연 리뷰 목록 (최신 공개순). 리뷰는 출연 앱에서 읽고 쓴 회원 정보를 붙인다 (#555) */
    @Override
    @GetMapping("/{memberId:\\d+}/appearance-reviews")
    public RsData<Page<AppearanceReviewResponse>> appearanceReviews(@PathVariable(name = "memberId") Long memberId,
                                                                    Pageable pageable) {
        return RsData.ok("받은 출연 리뷰를 조회했습니다.", memberFacade.findReceivedAppearanceReviews(memberId, pageable)
                .map(review -> AppearanceReviewResponse.from(review, review.reviewer() == null ? null
                        : memberFacade.profileImageUrl(review.reviewer().getProfileImageKey()))));
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

    /**
     * 탈퇴 회원 복구 (#593). 로그인하지 않고 복구 티켓으로 부르고, 계정만 되살린다(204). 로그인은 프론트가 이어서 다시 한다.
     * 복구 티켓으로 로그인까지 되면 공용 PC에 남은 티켓으로 그 계정을 빼앗을 수 있어서다 (#633).
     * 복구 티켓은 로그인 때 받은 복구 티켓 쿠키에서만 읽고, 복구하면 그 쿠키를 지운다.
     * 다시 시도해도 안 되는 실패(티켓 없음·만료 401, 기한 지남 403)도 쿠키를 지우고, 지갑 장애 503만 남겨 다시 시도할 수 있게 한다
     */
    @Override
    @PostMapping("/restore")
    public ResponseEntity<Void> restore(
            @CookieValue(name = RestoreTicketCookies.NAME, required = false) String restoreTicket,
            HttpServletResponse response) {
        try {
            memberFacade.restore(restoreTicket);
        } catch (MemberException e) {
            if (e.getErrorCode() == MemberErrorCode.INVALID_RESTORE_TICKET
                    || e.getErrorCode() == MemberErrorCode.LOGIN_NOT_ALLOWED) {
                response.addHeader(HttpHeaders.SET_COOKIE, restoreTicketCookies.expire().toString());
            }
            throw e;
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, restoreTicketCookies.expire().toString())
                .build();
    }

    /** 복구 취소 (#633). 복구 티켓을 지우고 티켓 쿠키도 지운다. 로그인하지 않고 부르고, 티켓이 없어도 204 */
    @Override
    @DeleteMapping("/restore")
    public ResponseEntity<Void> cancelRestore(
            @CookieValue(name = RestoreTicketCookies.NAME, required = false) String restoreTicket) {
        memberFacade.cancelRestore(restoreTicket);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, restoreTicketCookies.expire().toString())
                .build();
    }

    /** 회원 탈퇴 (#593). 성공하면 로그아웃처럼 refresh token 쿠키도 지운다. 이미 탈퇴했으면 그대로 성공 */
    @Override
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@LoginUserId Long userId) {
        memberFacade.withdraw(userId);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.expire().toString())
                .build();
    }

    /** 프로필 사진 삭제 (DB에서만 연결 해제, S3 파일은 그대로) */
    @Override
    @DeleteMapping("/me/profile-image")
    public RsData<MemberMeResponse> deleteProfileImage(@LoginUserId Long userId) {
        return RsData.ok("프로필 사진을 삭제했습니다.", meResponse(memberFacade.deleteProfileImage(userId)));
    }

    private MemberMeResponse meResponse(User user) {
        return MemberMeResponse.from(user, memberFacade.profileImageUrl(user.getProfileImageKey()),
                memberFacade.findContentCategoryNames(user.getContentCategories()));
    }
}
