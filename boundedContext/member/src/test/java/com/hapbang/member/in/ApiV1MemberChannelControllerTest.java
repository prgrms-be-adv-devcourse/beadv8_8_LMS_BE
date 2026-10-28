package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.HttpServerErrorException;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.ChzzkApiClient;
import com.hapbang.member.out.ChzzkUser;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserModifiedEvent;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;

/** 치지직은 부르지 않는다 (ChzzkApiClient를 가짜로 바꾼다) */
@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class ApiV1MemberChannelControllerTest {

    private static final String CALLBACK = "/api/v1/members/channels/chzzk/callback";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Autowired
    ApplicationEvents events;

    @MockitoBean
    ChzzkApiClient chzzkApiClient;

    @Test
    @DisplayName("연동 시작: state를 세션에 보관하고 같은 state로 치지직 화면에 보낸다")
    void connect() throws Exception {
        User user = saveUser(Role.USER);
        given(chzzkApiClient.authorizeUrl(any())).willAnswer(call -> "https://chzzk.example/auth?state=" + call.getArgument(0));
        MockHttpSession session = new MockHttpSession();

        MvcResult result = mockMvc.perform(get("/api/v1/members/channels/chzzk/connect")
                        .session(session).with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isFound())
                .andReturn();

        String state = (String) session.getAttribute(ApiV1MemberChannelController.CHZZK_STATE);
        assertThat(state).isNotBlank();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("https://chzzk.example/auth?state=" + state);
    }

    @Test
    @DisplayName("연동 시작: 유저가 아니면 403")
    void connect_notUser() throws Exception {
        User influencer = saveUser(Role.INFLUENCER);

        mockMvc.perform(get("/api/v1/members/channels/chzzk/connect")
                        .with(TestLogin.as(influencer.getId(), influencer.getRole())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("콜백: 팔로워 10명 이상이면 자동 승인 → 인플루언서, 이벤트 발행, 세션 권한도 바뀐다")
    void callback_approved() throws Exception {
        User user = saveUser(Role.USER);
        givenChzzkChannel("channel-1", 12);
        MockHttpSession session = sessionWithState("state-1");

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .session(session).with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/mypage?chzzk=approved"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(influencerChannelRepository.findAll()).singleElement()
                .satisfies(channel -> {
                    assertThat(channel.getChannelIdentifier()).isEqualTo("channel-1");
                    assertThat(channel.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.APPROVED);
                });
        assertThat(events.stream(UserModifiedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.user().role()).isEqualTo(Role.INFLUENCER));
        assertThat(sessionRole(session)).isEqualTo("ROLE_INFLUENCER");
        assertThat(session.getAttribute(ApiV1MemberChannelController.CHZZK_STATE)).isNull();
        verify(chzzkApiClient).revokeToken("token-1");
    }

    @Test
    @DisplayName("콜백: 팔로워 10명 미만이면 등록만 되고 유저 그대로")
    void callback_registered() throws Exception {
        User user = saveUser(Role.USER);
        givenChzzkChannel("channel-1", 9);

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .session(sessionWithState("state-1")).with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(header().string("Location", "/mypage?chzzk=registered"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
        assertThat(influencerChannelRepository.findAll()).singleElement()
                .satisfies(channel -> assertThat(channel.getVerificationStatus())
                        .isEqualTo(ChannelVerificationStatus.REGISTERED));
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("콜백: state가 다르면 치지직을 부르지 않고 실패 화면으로")
    void callback_invalidState() throws Exception {
        User user = saveUser(Role.USER);

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "other")
                        .session(sessionWithState("state-1")).with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=INVALID_CHZZK_STATE"));

        verify(chzzkApiClient, never()).issueToken(any(), any());
    }

    @Test
    @DisplayName("콜백: 이미 등록된 채널이면 실패 화면으로")
    void callback_alreadyRegistered() throws Exception {
        User owner = saveUser(Role.USER);
        influencerChannelRepository.save(InfluencerChannel.register(owner, ChannelPlatform.CHZZK, "channel-1",
                "방송인", 3, LocalDateTime.now()));
        User user = saveUser(Role.USER);
        givenChzzkChannel("channel-1", 12);

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .session(sessionWithState("state-1")).with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=CHANNEL_ALREADY_REGISTERED"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("콜백: 정상 회원이 아니면(탈퇴 등) 치지직을 부르지 않고 실패 화면으로")
    void callback_withdrawn() throws Exception {
        User user = saveUser(Role.USER);
        withdraw(user);

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .session(sessionWithState("state-1")).with(TestLogin.as(user.getId(), Role.USER)))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=MEMBER_NOT_FOUND"));

        verify(chzzkApiClient, never()).issueToken(any(), any());
    }

    @Test
    @DisplayName("콜백: 치지직이 요청을 거절하면 실패 화면으로 (code=CHZZK_REQUEST_REJECTED)")
    void callback_rejected() throws Exception {
        User user = saveUser(Role.USER);
        given(chzzkApiClient.issueToken("code-1", "state-1"))
                .willThrow(new MemberException(MemberErrorCode.CHZZK_REQUEST_REJECTED));

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .session(sessionWithState("state-1")).with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=CHZZK_REQUEST_REJECTED"));
    }

    @Test
    @DisplayName("콜백: 치지직 장애여도 토큰은 폐기하고 실패 화면으로 (code=INTERNAL_ERROR)")
    void callback_chzzkFailed() throws Exception {
        User user = saveUser(Role.USER);
        given(chzzkApiClient.issueToken("code-1", "state-1")).willReturn("token-1");
        given(chzzkApiClient.findMe("token-1")).willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .session(sessionWithState("state-1")).with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=INTERNAL_ERROR"));

        verify(chzzkApiClient).revokeToken("token-1");
        assertThat(influencerChannelRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("승인 요청: 다시 조회한 팔로워가 10명 이상이면 자동 승인 → 인플루언서, 이벤트 발행, 세션 권한도 바뀐다")
    void approvalRequest_approved() throws Exception {
        User user = saveUser(Role.USER);
        InfluencerChannel channel = saveChannel(user, "channel-1", 5);
        given(chzzkApiClient.findFollowerCount("channel-1")).willReturn(15);
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post(approvalRequest(channel)).session(session)
                        .with(TestLogin.as(user.getId(), user.getRole())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("채널이 승인되었습니다."))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.role").value("INFLUENCER"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
        InfluencerChannel saved = influencerChannelRepository.findById(channel.getId()).orElseThrow();
        assertThat(saved.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.APPROVED);
        assertThat(saved.getFollowerCount()).isEqualTo(15);
        assertThat(events.stream(UserModifiedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.user().role()).isEqualTo(Role.INFLUENCER));
        assertThat(sessionRole(session)).isEqualTo("ROLE_INFLUENCER");
    }

    @Test
    @DisplayName("승인 요청: 팔로워가 여전히 10명 미만이면 관리자 심사 대기, 역할은 유저 그대로")
    void approvalRequest_pending() throws Exception {
        User user = saveUser(Role.USER);
        InfluencerChannel channel = saveChannel(user, "channel-1", 5);
        given(chzzkApiClient.findFollowerCount("channel-1")).willReturn(7);

        mockMvc.perform(post(approvalRequest(channel))
                        .with(TestLogin.as(user.getId(), user.getRole())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("채널 승인을 요청했습니다."))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.role").value("USER"));

        InfluencerChannel saved = influencerChannelRepository.findById(channel.getId()).orElseThrow();
        assertThat(saved.getVerificationStatus()).isEqualTo(ChannelVerificationStatus.PENDING);
        assertThat(saved.getRequestedAt()).isNotNull();
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("승인 요청: 남의 채널 번호면 404, 치지직을 부르지 않고 그 채널과 내 역할은 그대로")
    void approvalRequest_notMine() throws Exception {
        User owner = saveUser(Role.USER);
        InfluencerChannel othersChannel = saveChannel(owner, "channel-1", 500);
        User attacker = saveUser(Role.USER);

        mockMvc.perform(post(approvalRequest(othersChannel))
                        .with(TestLogin.as(attacker.getId(), attacker.getRole())).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));

        verify(chzzkApiClient, never()).findFollowerCount(any());
        assertThat(influencerChannelRepository.findById(othersChannel.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(ChannelVerificationStatus.APPROVED);
        assertThat(userRepository.findById(attacker.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("승인 요청: 정상 회원이 아니면(탈퇴 등) 404, 치지직을 부르지 않는다")
    void approvalRequest_withdrawn() throws Exception {
        User user = saveUser(Role.USER);
        InfluencerChannel channel = saveChannel(user, "channel-1", 5);
        withdraw(user);

        mockMvc.perform(post(approvalRequest(channel))
                        .with(TestLogin.as(user.getId(), Role.USER)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));

        verify(chzzkApiClient, never()).findFollowerCount(any());
    }

    @Test
    @DisplayName("승인 요청: 이미 심사 대기 중이면 409, 치지직을 부르지 않는다")
    void approvalRequest_alreadyPending() throws Exception {
        User user = saveUser(Role.USER);
        InfluencerChannel channel = InfluencerChannel.register(user, ChannelPlatform.CHZZK, "channel-1", "방송인", 5,
                LocalDateTime.now());
        channel.requestApproval(6, LocalDateTime.now());
        influencerChannelRepository.save(channel);

        mockMvc.perform(post(approvalRequest(channel))
                        .with(TestLogin.as(user.getId(), user.getRole())).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANNEL_APPROVAL_NOT_ALLOWED"));

        verify(chzzkApiClient, never()).findFollowerCount(any());
    }

    @Test
    @DisplayName("승인 요청: 유저가 아니면 403")
    void approvalRequest_notUser() throws Exception {
        User influencer = saveUser(Role.INFLUENCER);
        InfluencerChannel channel = saveChannel(influencer, "channel-1", 5);

        mockMvc.perform(post(approvalRequest(channel))
                        .with(TestLogin.as(influencer.getId(), influencer.getRole())).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("승인 요청: 치지직 장애면 공통 500 INTERNAL_ERROR, 채널 상태는 그대로")
    void approvalRequest_chzzkFailed() throws Exception {
        User user = saveUser(Role.USER);
        InfluencerChannel channel = saveChannel(user, "channel-1", 5);
        given(chzzkApiClient.findFollowerCount("channel-1"))
                .willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        mockMvc.perform(post(approvalRequest(channel))
                        .with(TestLogin.as(user.getId(), user.getRole())).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(influencerChannelRepository.findById(channel.getId()).orElseThrow().getVerificationStatus())
                .isEqualTo(ChannelVerificationStatus.REGISTERED);
    }

    @Test
    @DisplayName("내 채널 목록: 내 채널만 상태와 함께 준다")
    void myChannels() throws Exception {
        User user = saveUser(Role.USER);
        saveChannel(user, "channel-1", 5);
        saveChannel(user, "channel-2", 20);
        saveChannel(saveUser(Role.USER), "channel-3", 30);

        mockMvc.perform(get("/api/v1/members/channels/me").with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].channelUrl").value("https://chzzk.naver.com/channel-1"))
                .andExpect(jsonPath("$.data[0].status").value("REGISTERED"))
                .andExpect(jsonPath("$.data[0].followerCount").value(5))
                .andExpect(jsonPath("$.data[0].followerCountAt").isNotEmpty())
                .andExpect(jsonPath("$.data[1].status").value("APPROVED"));
    }

    private User saveUser(Role role) {
        User user = User.join("tester" + System.nanoTime() + "@example.com", "tester1");
        user.changeRole(role);
        return userRepository.save(user);
    }

    private void withdraw(User user) {
        ReflectionTestUtils.setField(user, "status", UserStatus.WITHDRAWN);
        userRepository.save(user);
    }

    private InfluencerChannel saveChannel(User user, String channelId, int followerCount) {
        return influencerChannelRepository.save(InfluencerChannel.register(user, ChannelPlatform.CHZZK, channelId,
                "방송인", followerCount, LocalDateTime.now()));
    }

    private String approvalRequest(InfluencerChannel channel) {
        return "/api/v1/members/channels/" + channel.getId() + "/approval-request";
    }

    private void givenChzzkChannel(String channelId, int followerCount) {
        given(chzzkApiClient.issueToken("code-1", "state-1")).willReturn("token-1");
        given(chzzkApiClient.findMe("token-1")).willReturn(new ChzzkUser(channelId, "방송인"));
        given(chzzkApiClient.findFollowerCount(channelId)).willReturn(followerCount);
    }

    private MockHttpSession sessionWithState(String state) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ApiV1MemberChannelController.CHZZK_STATE, state);
        return session;
    }

    private String sessionRole(MockHttpSession session) {
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context.getAuthentication().getAuthorities().iterator().next().getAuthority();
    }
}
