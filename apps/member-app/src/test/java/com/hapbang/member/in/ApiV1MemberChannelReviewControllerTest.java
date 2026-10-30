package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.TestLogin;

/** 관리자 채널 심사 API: 응답 모양·상태 코드·권한 (처리 규칙 자세한 경우는 MemberChannelReviewIntegrationTest) */
@MemberIntegrationTest
@AutoConfigureMockMvc
class ApiV1MemberChannelReviewControllerTest {

    private static final String BASE = "/api/v1/members/admin/channels";
    private static final Long ADMIN_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final AtomicInteger CHANNEL_SEQUENCE = new AtomicInteger();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Test
    @DisplayName("목록: 관리자는 심사 대기 채널을 채널·회원 정보와 페이지 정보로 받는다")
    void channels() throws Exception {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel channel = pendingChannel(user);

        mockMvc.perform(get(BASE).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content[0].channelId").value(channel.getId()))
                .andExpect(jsonPath("$.data.content[0].memberId").value(user.getId()))
                .andExpect(jsonPath("$.data.content[0].nickname").value(user.getNickname()))
                .andExpect(jsonPath("$.data.content[0].channelUrl").value(channel.getChannelUrl()))
                .andExpect(jsonPath("$.data.content[0].followerCount").value(5))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.content[0].verifiedBy").doesNotExist())
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }

    @Test
    @DisplayName("목록: 잘못된 상태 값은 400")
    void channels_invalidStatus() throws Exception {
        mockMvc.perform(get(BASE).param("status", "ABC").with(asAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("승인: 204, 채널 승인 + 유저는 인플루언서")
    void approve() throws Exception {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel channel = pendingChannel(user);

        mockMvc.perform(post(approvePath(channel)).with(asAdmin()).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(influencerChannelRepository.findById(channel.getId()).orElseThrow().getVerifiedBy())
                .isEqualTo(ADMIN_ID);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
    }

    @Test
    @DisplayName("반려: 204, 사유 저장")
    void reject() throws Exception {
        InfluencerChannel channel = pendingChannel(userRepository.save(MemberFixture.user()));

        mockMvc.perform(reject(channel, "{\"reason\":\"방송 시간 부족\"}"))
                .andExpect(status().isNoContent());

        InfluencerChannel saved = influencerChannelRepository.findById(channel.getId()).orElseThrow();
        assertThat(saved.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.REJECTED);
        assertThat(saved.getRejectionReason()).isEqualTo("방송 시간 부족");
    }

    @Test
    @DisplayName("반려: 사유가 비었거나 501자면 400 INVALID_REQUEST, 필드는 reason")
    void reject_invalidReason() throws Exception {
        InfluencerChannel channel = pendingChannel(userRepository.save(MemberFixture.user()));

        for (String body : new String[] {"{\"reason\":\"  \"}", "{}", "{\"reason\":\"" + "가".repeat(501) + "\"}"}) {
            mockMvc.perform(reject(channel, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errors[0].field").value("reason"));
        }
        assertThat(influencerChannelRepository.findById(channel.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(ChannelVerificationStatus.PENDING);
    }

    @Test
    @DisplayName("심사 대기가 아니면 409, 탈퇴 회원 채널이면 409, 없는 채널이면 404")
    void approve_errors() throws Exception {
        InfluencerChannel registered = influencerChannelRepository.save(InfluencerChannel.register(
                userRepository.save(MemberFixture.user()), ChannelPlatform.CHZZK, nextChannelId(), "테스트 채널", 5, NOW));
        InfluencerChannel withdrawn = pendingChannel(
                userRepository.save(MemberFixture.member(Role.USER, UserStatus.WITHDRAWN)));

        mockMvc.perform(post(approvePath(registered)).with(asAdmin()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANNEL_REVIEW_NOT_ALLOWED"));
        mockMvc.perform(post(approvePath(withdrawn)).with(asAdmin()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANNEL_OWNER_INACTIVE"));
        mockMvc.perform(post(BASE + "/999999/approve").with(asAdmin()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 로그인 안 했으면 401 (URL 규칙에서 막혀 본문 없음)")
    void notAdmin() throws Exception {
        User user = userRepository.save(MemberFixture.user());
        InfluencerChannel channel = pendingChannel(user);

        mockMvc.perform(get(BASE).with(TestLogin.as(user.getId(), Role.USER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(approvePath(channel)).with(TestLogin.as(user.getId(), Role.INFLUENCER)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized());
        assertThat(influencerChannelRepository.findById(channel.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(ChannelVerificationStatus.PENDING);
    }

    private RequestPostProcessor asAdmin() {
        return TestLogin.as(ADMIN_ID, Role.ADMIN);
    }

    private MockHttpServletRequestBuilder reject(InfluencerChannel channel, String body) {
        return post(BASE + "/" + channel.getId() + "/reject").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String approvePath(InfluencerChannel channel) {
        return BASE + "/" + channel.getId() + "/approve";
    }

    private InfluencerChannel pendingChannel(User user) {
        InfluencerChannel channel = InfluencerChannel.register(user, ChannelPlatform.CHZZK, nextChannelId(),
                "테스트 채널", 5, NOW);
        channel.requestApproval(5, NOW);
        return influencerChannelRepository.save(channel);
    }

    private String nextChannelId() {
        return "review-channel-" + CHANNEL_SEQUENCE.incrementAndGet();
    }
}
