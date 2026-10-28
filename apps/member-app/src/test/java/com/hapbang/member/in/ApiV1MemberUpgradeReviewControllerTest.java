package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.InfluencerUpgradeRequest;
import com.hapbang.member.domain.UpgradeRequestStatus;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.InfluencerUpgradeRequestRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

/** 관리자 승급 심사 API: 응답 모양·상태 코드·권한 (처리 규칙 자세한 경우는 MemberUpgradeReviewIntegrationTest) */
@IntegrationTest
@AutoConfigureMockMvc
class ApiV1MemberUpgradeReviewControllerTest {

    private static final String BASE = "/api/v1/members/admin/upgrade-requests";
    private static final Long ADMIN_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 0);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerUpgradeRequestRepository influencerUpgradeRequestRepository;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Test
    @DisplayName("목록: 관리자는 신청·회원·승인된 채널과 페이지 정보를 받는다")
    void requests() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerChannel channel = influencerChannelRepository.save(InfluencerChannel.register(influencer,
                ChannelPlatform.CHZZK, "upgrade-review-channel", "방송 채널", 20, NOW));
        InfluencerUpgradeRequest request = pendingRequest(influencer);

        mockMvc.perform(get(BASE).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].requestId").value(request.getId()))
                .andExpect(jsonPath("$.data.content[0].memberId").value(influencer.getId()))
                .andExpect(jsonPath("$.data.content[0].nickname").value(influencer.getNickname()))
                .andExpect(jsonPath("$.data.content[0].requestReason").value("강의 경력 3년"))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.content[0].channels[0].channelUrl").value(channel.getChannelUrl()))
                .andExpect(jsonPath("$.data.content[0].channels[0].followerCount").value(20))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }

    @Test
    @DisplayName("승인: 204, 회원은 인증된 인플루언서")
    void approve() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest request = pendingRequest(influencer);

        mockMvc.perform(post(BASE + "/" + request.getId() + "/approve").with(asAdmin()).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(userRepository.findById(influencer.getId()).orElseThrow().getRole())
                .isEqualTo(Role.VERIFIED_INFLUENCER);
    }

    @Test
    @DisplayName("반려: 204, 사유 저장 / 사유가 비면 400 INVALID_REQUEST")
    void reject() throws Exception {
        InfluencerUpgradeRequest request = pendingRequest(userRepository.save(MemberFixture.member(Role.INFLUENCER)));

        mockMvc.perform(reject(request, "{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mockMvc.perform(reject(request, "{\"reason\":\"활동 내역 부족\"}"))
                .andExpect(status().isNoContent());

        InfluencerUpgradeRequest saved = influencerUpgradeRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UpgradeRequestStatus.REJECTED);
        assertThat(saved.getRejectionReason()).isEqualTo("활동 내역 부족");
    }

    @Test
    @DisplayName("없는 신청 404, 이미 처리한 신청 409")
    void approve_errors() throws Exception {
        InfluencerUpgradeRequest request = pendingRequest(userRepository.save(MemberFixture.member(Role.INFLUENCER)));
        mockMvc.perform(reject(request, "{\"reason\":\"사유\"}")).andExpect(status().isNoContent());

        mockMvc.perform(post(BASE + "/999999/approve").with(asAdmin()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UPGRADE_REQUEST_NOT_FOUND"));
        mockMvc.perform(post(BASE + "/" + request.getId() + "/approve").with(asAdmin()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPGRADE_REQUEST_REVIEW_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 로그인 안 했으면 401 (URL 규칙에서 막혀 본문 없음)")
    void notAdmin() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        InfluencerUpgradeRequest request = pendingRequest(influencer);

        mockMvc.perform(get(BASE).with(TestLogin.as(influencer.getId(), Role.INFLUENCER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/" + request.getId() + "/approve")
                        .with(TestLogin.as(influencer.getId(), Role.INFLUENCER)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
        assertThat(influencerUpgradeRequestRepository.findById(request.getId()).orElseThrow().isPending()).isTrue();
    }

    private RequestPostProcessor asAdmin() {
        return TestLogin.as(ADMIN_ID, Role.ADMIN);
    }

    private MockHttpServletRequestBuilder reject(InfluencerUpgradeRequest request, String body) {
        return post(BASE + "/" + request.getId() + "/reject").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private InfluencerUpgradeRequest pendingRequest(User user) {
        return influencerUpgradeRequestRepository.save(InfluencerUpgradeRequest.request(user, "강의 경력 3년", NOW));
    }
}
