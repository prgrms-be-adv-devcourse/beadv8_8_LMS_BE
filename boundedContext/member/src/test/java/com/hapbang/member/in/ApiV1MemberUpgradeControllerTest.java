package com.hapbang.member.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.InfluencerUpgradeRequestRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

/** 승급 신청 API: 응답 모양·상태 코드·권한 (처리 규칙 자세한 경우는 MemberUpgradeRequestIntegrationTest) */
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1MemberUpgradeControllerTest {

    private static final String BASE = "/api/v1/members/upgrade-requests";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerUpgradeRequestRepository influencerUpgradeRequestRepository;

    @Test
    @DisplayName("신청: 인플루언서는 201, 심사 대기로 저장되고 사유는 앞뒤 공백을 지운다")
    void request() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));

        mockMvc.perform(apply(influencer, "{\"reason\":\"  강의 경력 3년  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.requestReason").value("강의 경력 3년"))
                .andExpect(jsonPath("$.data.requestedAt").exists())
                .andExpect(jsonPath("$.data.reviewedBy").doesNotExist());
    }

    @Test
    @DisplayName("신청: 심사 대기 중이면 409")
    void request_alreadyPending() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        mockMvc.perform(apply(influencer, "{\"reason\":\"첫 신청\"}")).andExpect(status().isCreated());

        mockMvc.perform(apply(influencer, "{\"reason\":\"또 신청\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPGRADE_REQUEST_ALREADY_PENDING"));
    }

    @Test
    @DisplayName("신청: 사유가 비었거나 501자면 400 INVALID_REQUEST, 필드는 reason")
    void request_invalidReason() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));

        for (String body : new String[] {"{\"reason\":\"  \"}", "{}", "{\"reason\":\"" + "가".repeat(501) + "\"}"}) {
            mockMvc.perform(apply(influencer, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errors[0].field").value("reason"));
        }
    }

    @Test
    @DisplayName("신청: 유저·인증된 인플루언서는 403, 비로그인은 401")
    void request_notInfluencer() throws Exception {
        User user = userRepository.save(MemberFixture.user());
        User verified = userRepository.save(MemberFixture.member(Role.VERIFIED_INFLUENCER));

        mockMvc.perform(apply(user, "{\"reason\":\"사유\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(apply(verified, "{\"reason\":\"사유\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사유\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내 신청 내역: 최신순, 반려 사유 포함, 처리 관리자 ID는 없다")
    void myRequests() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest rejected = influencerUpgradeRequestRepository.save(
                InfluencerUpgradeRequest.request(influencer, "첫 신청", LocalDateTime.now()));
        rejected.reject(99L, "활동 내역 부족", LocalDateTime.now());
        influencerUpgradeRequestRepository.save(rejected);
        mockMvc.perform(apply(influencer, "{\"reason\":\"다시 신청\"}")).andExpect(status().isCreated());

        mockMvc.perform(get(BASE + "/me").with(TestLogin.as(influencer.getId(), influencer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[1].rejectionReason").value("활동 내역 부족"))
                .andExpect(jsonPath("$.data[1].reviewedBy").doesNotExist());
    }

    private MockHttpServletRequestBuilder apply(User loginUser, String body) {
        return post(BASE).with(TestLogin.as(loginUser.getId(), loginUser.getRole())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
