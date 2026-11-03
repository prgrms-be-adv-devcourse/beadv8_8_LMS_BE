package com.hapbang.member.in;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.GatewayLogin;
import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewDto;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewPageDto;
import com.hapbang.shared.appearance.out.AppearanceApiCallException;
import com.hapbang.shared.appearance.out.AppearanceApiClient;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 회원이 받은 출연 리뷰 목록 (#555). 출연 내부 API Client는 대역으로 두고 회원 쪽 조립(쓴 회원 붙이기, 오류 변환)을 확인한다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
class AppearanceReviewApiTest {

    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 11, 2, 15, 30);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    AppearanceApiClient appearanceApiClient;

    @Test
    @DisplayName("받은 리뷰에 쓴 회원의 닉네임을 붙이고, 탈퇴했거나 없는 회원은 '탈퇴한 회원'으로 보여 준다")
    void list() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User target = saveMember("target1", Role.INFLUENCER, UserStatus.ACTIVE);
        User active = saveMember("active1", Role.INFLUENCER, UserStatus.ACTIVE);
        User suspended = saveMember("suspended1", Role.INFLUENCER, UserStatus.SUSPENDED);
        User withdrawn = saveMember("withdrawn1", Role.INFLUENCER, UserStatus.WITHDRAWN);
        long missingId = 9_999_999L;
        given(appearanceApiClient.getReceivedReviews(target.getId(), 0, 20)).willReturn(page(List.of(
                review(4, active.getId(), AppearanceParticipantRole.HOST, 5, "최고였어요"),
                review(3, suspended.getId(), AppearanceParticipantRole.GUEST, 4, null),
                review(2, withdrawn.getId(), AppearanceParticipantRole.HOST, 3, "보통"),
                review(1, missingId, AppearanceParticipantRole.GUEST, 2, null)), 0, 20, 4));

        mockMvc.perform(get("/api/v1/members/{memberId}/appearance-reviews", target.getId())
                        .with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("받은 출연 리뷰를 조회했습니다."))
                .andExpect(jsonPath("$.data.content.length()").value(4))
                .andExpect(jsonPath("$.data.content[0].reviewId").value(4))
                .andExpect(jsonPath("$.data.content[0].appearanceId").value(104))
                .andExpect(jsonPath("$.data.content[0].revieweeRole").value("HOST"))
                .andExpect(jsonPath("$.data.content[0].rating").value(5))
                .andExpect(jsonPath("$.data.content[0].content").value("최고였어요"))
                .andExpect(jsonPath("$.data.content[0].publishedAt").value("2026-11-02T15:30:00"))
                .andExpect(jsonPath("$.data.content[0].reviewer.id").value(active.getId()))
                .andExpect(jsonPath("$.data.content[0].reviewer.nickname").value("active1"))
                .andExpect(jsonPath("$.data.content[0].reviewer.profileImageUrl").value(nullValue()))
                // 정지된 회원은 그대로 보인다
                .andExpect(jsonPath("$.data.content[1].reviewer.nickname").value("suspended1"))
                .andExpect(jsonPath("$.data.content[1].content").value(nullValue()))
                .andExpect(jsonPath("$.data.content[2].reviewer.id").value(withdrawn.getId()))
                .andExpect(jsonPath("$.data.content[2].reviewer.nickname").value("탈퇴한 회원"))
                .andExpect(jsonPath("$.data.content[3].reviewer.id").value(missingId))
                .andExpect(jsonPath("$.data.content[3].reviewer.nickname").value("탈퇴한 회원"))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.totalElements").value(4))
                .andExpect(jsonPath("$.data.page.totalPages").value(1));
    }

    @Test
    @DisplayName("요청한 페이지를 출연 API에 그대로 넘기고, 크기는 최대 100으로 맞춘다")
    void paging() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User target = saveMember("target1", Role.INFLUENCER, UserStatus.ACTIVE);
        given(appearanceApiClient.getReceivedReviews(target.getId(), 2, 100)).willReturn(page(List.of(), 2, 100, 0));

        mockMvc.perform(get("/api/v1/members/{memberId}/appearance-reviews?page=2&size=500", target.getId())
                        .with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.page.number").value(2))
                .andExpect(jsonPath("$.data.page.size").value(100));
    }

    @Test
    @DisplayName("출연 API를 부를 수 없으면 503 APPEARANCE_REVIEW_UNAVAILABLE")
    void unavailable() throws Exception {
        assertUnavailable(AppearanceApiCallException.Reason.UNAVAILABLE);
    }

    @Test
    @DisplayName("출연 API가 거절해도(내부 키·계약 불일치) 사용자에게는 503 APPEARANCE_REVIEW_UNAVAILABLE")
    void rejected() throws Exception {
        assertUnavailable(AppearanceApiCallException.Reason.REJECTED);
    }

    @Test
    @DisplayName("탈퇴한 회원의 받은 리뷰는 프로필과 같이 404이고 출연 API를 부르지 않는다")
    void withdrawnTarget() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User withdrawn = saveMember("withdrawn1", Role.INFLUENCER, UserStatus.WITHDRAWN);

        mockMvc.perform(get("/api/v1/members/{memberId}/appearance-reviews", withdrawn.getId())
                        .with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        verify(appearanceApiClient, never()).getReceivedReviews(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/v1/members/{memberId}/appearance-reviews", 1L))
                .andExpect(status().isUnauthorized());
    }

    private void assertUnavailable(AppearanceApiCallException.Reason reason) throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User target = saveMember("target1", Role.INFLUENCER, UserStatus.ACTIVE);
        // 생성자가 Client 패키지 안에만 열려 있어 대역으로 만든다
        AppearanceApiCallException exception = mock(AppearanceApiCallException.class);
        given(exception.getReason()).willReturn(reason);
        given(appearanceApiClient.getReceivedReviews(anyLong(), anyInt(), anyInt())).willThrow(exception);

        mockMvc.perform(get("/api/v1/members/{memberId}/appearance-reviews", target.getId())
                        .with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("APPEARANCE_REVIEW_UNAVAILABLE"));
    }

    private static AppearanceReceivedReviewDto review(long reviewId, long reviewerId, AppearanceParticipantRole role,
                                                      int rating, String content) {
        return new AppearanceReceivedReviewDto(reviewId, 100 + reviewId, reviewerId, role, rating, content, PUBLISHED_AT);
    }

    private static AppearanceReceivedReviewPageDto page(List<AppearanceReceivedReviewDto> content, long number,
                                                        long size, long totalElements) {
        long totalPages = (totalElements + size - 1) / size;
        return new AppearanceReceivedReviewPageDto(content,
                new AppearanceReceivedReviewPageDto.PageMetadata(size, number, totalElements, totalPages));
    }

    /** 탈퇴 기능(F7)이 아직 없어 상태는 테스트에서만 직접 바꾼다 */
    private User saveMember(String nickname, Role role, UserStatus status) {
        User user = User.join(nickname + "@example.com", nickname);
        user.changeRole(role);
        ReflectionTestUtils.setField(user, "status", status);
        return userRepository.saveAndFlush(user);
    }
}
