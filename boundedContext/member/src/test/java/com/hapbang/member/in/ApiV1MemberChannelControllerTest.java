package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
                "방송인", 3, java.time.LocalDateTime.now()));
        User user = saveUser(Role.USER);
        givenChzzkChannel("channel-1", 12);

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .session(sessionWithState("state-1")).with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=CHANNEL_ALREADY_REGISTERED"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("콜백: 치지직 조회가 실패해도 토큰은 폐기하고 실패 화면으로")
    void callback_chzzkFailed() throws Exception {
        User user = saveUser(Role.USER);
        given(chzzkApiClient.issueToken("code-1", "state-1")).willReturn("token-1");
        given(chzzkApiClient.findMe("token-1")).willThrow(new MemberException(MemberErrorCode.CHZZK_API_FAILED));

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .session(sessionWithState("state-1")).with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=CHZZK_API_FAILED"));

        verify(chzzkApiClient).revokeToken("token-1");
        assertThat(influencerChannelRepository.findAll()).isEmpty();
    }

    private User saveUser(Role role) {
        User user = User.join("tester" + System.nanoTime() + "@example.com", "tester1");
        user.changeRole(role);
        return userRepository.save(user);
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
