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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.HttpServerErrorException;

import com.hapbang.member.MemberIntegrationTest;
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
import com.hapbang.testsupport.RedisTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/** 치지직은 부르지 않는다 (ChzzkApiClient를 가짜로 바꾼다) */
@MemberIntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(RedisTestcontainersConfig.class)
class ApiV1MemberChannelControllerTest {

    private static final String CALLBACK = "/api/v1/members/channels/chzzk/callback";
    private static final String AUTHORIZE = "/api/v1/members/channels/chzzk/authorize";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcIndexedSessionRepository sessionRepository;

    @MockitoBean
    ChzzkApiClient chzzkApiClient;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 테스트끼리 Redis를 같이 쓰므로 자기가 만든 키만 지운다 (docs/dev/testing.md) */
    private final List<String> createdRedisKeys = new ArrayList<>();

    @AfterEach
    void cleanUpRedis() {
        redisTemplate.delete(createdRedisKeys);
    }

    @Test
    @DisplayName("연동 시작(API, #370): 치지직 주소를 본문으로 주고, 연동하는 회원과 state를 Redis에 맡기고 브라우저 쿠키를 준다")
    void authorize() throws Exception {
        User user = saveUser(Role.USER);

        ChzzkStart start = authorize(user);

        assertThat(start.setCookie())
                .startsWith(ApiV1MemberChannelController.CHZZK_AUTH_COOKIE + "=")
                .contains("Path=/api/v1/members/channels/chzzk", "Max-Age=600", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(redisTemplate.opsForValue().get("member:chzzk-state:" + start.browserId()))
                .isEqualTo(user.getId() + ":" + start.state());
    }

    @Test
    @DisplayName("연동 시작(API): 유저가 아니면 403")
    void authorize_notUser() throws Exception {
        User influencer = saveUser(Role.INFLUENCER);

        mockMvc.perform(post(AUTHORIZE).with(TestLogin.as(influencer.getId(), influencer.getRole())).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("콜백(#370): 로그인 정보 없이도 시작한 브라우저 쿠키로 맡긴 회원을 찾아 연동하고, 쿠키를 지운다")
    void callback_authorizedApproved() throws Exception {
        User user = saveUser(Role.USER);
        ChzzkStart start = authorize(user);
        givenChzzkChannel("channel-1", 12, start.state());

        String setCookie = mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", start.state())
                        .cookie(new Cookie(ApiV1MemberChannelController.CHZZK_AUTH_COOKIE, start.browserId())))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/mypage?chzzk=approved"))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(setCookie).startsWith(ApiV1MemberChannelController.CHZZK_AUTH_COOKIE + "=;").contains("Max-Age=0");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(influencerChannelRepository.findAll()).singleElement()
                .satisfies(channel -> assertThat(channel.getChannelIdentifier()).isEqualTo("channel-1"));
    }

    @Test
    @DisplayName("콜백(#370): 연동을 시작한 쿠키가 없는 브라우저는 실패한다 (남의 동의 링크로 내 채널이 남의 계정에 붙는 것을 막는다)")
    void callback_authorizedOtherBrowser() throws Exception {
        User user = saveUser(Role.USER);
        ChzzkStart start = authorize(user);

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", start.state()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=INVALID_CHZZK_STATE"));

        assertThat(influencerChannelRepository.findAll()).isEmpty();
        verify(chzzkApiClient, never()).issueToken(any(), any());
    }

    @Test
    @DisplayName("콜백(#370): state가 다르면 실패하고, 맡긴 연동은 지워져 다시 쓸 수 없다")
    void callback_authorizedStateMismatch() throws Exception {
        User user = saveUser(Role.USER);
        ChzzkStart start = authorize(user);
        Cookie browser = new Cookie(ApiV1MemberChannelController.CHZZK_AUTH_COOKIE, start.browserId());

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "other-state").cookie(browser))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=INVALID_CHZZK_STATE"));
        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", start.state()).cookie(browser))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=INVALID_CHZZK_STATE"));

        verify(chzzkApiClient, never()).issueToken(any(), any());
    }

    @Test
    @DisplayName("콜백(#370): 시작한 뒤 유저가 아니게 됐으면 연동하지 않는다")
    void callback_authorizedNotUserAnymore() throws Exception {
        User user = saveUser(Role.USER);
        ChzzkStart start = authorize(user);
        user.changeRole(Role.INFLUENCER);
        userRepository.save(user);

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", start.state())
                        .cookie(new Cookie(ApiV1MemberChannelController.CHZZK_AUTH_COOKIE, start.browserId())))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=CHANNEL_CONNECT_NOT_ALLOWED"));

        verify(chzzkApiClient, never()).issueToken(any(), any());
    }

    @Test
    @DisplayName("콜백(#370): 치지직 동의를 취소해 code 없이 돌아오면 실패")
    void callback_authorizedRejected() throws Exception {
        User user = saveUser(Role.USER);
        ChzzkStart start = authorize(user);

        mockMvc.perform(get(CALLBACK).param("state", start.state())
                        .cookie(new Cookie(ApiV1MemberChannelController.CHZZK_AUTH_COOKIE, start.browserId())))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=CHZZK_REQUEST_REJECTED"));
    }

    @Test
    @DisplayName("연동 시작: state를 세션에 보관하고 같은 state로 치지직 화면에 보낸다")
    void connect() throws Exception {
        User user = saveUser(Role.USER);
        given(chzzkApiClient.authorizeUrl(any())).willAnswer(call -> "https://chzzk.example/auth?state=" + call.getArgument(0));
        Session session = sessionWithState(user, null);

        MvcResult result = mockMvc.perform(get("/api/v1/members/channels/chzzk/connect")
                        .cookie(cookie(session)))
                .andExpect(status().isFound())
                .andReturn();

        String state = (String) storedSession(session).getAttribute(ApiV1MemberChannelController.CHZZK_STATE);
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
        Session session = sessionWithState(user, "state-1");

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .cookie(cookie(session)))
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
        assertThat((Object) storedSession(session).getAttribute(ApiV1MemberChannelController.CHZZK_STATE)).isNull();
        verify(chzzkApiClient).revokeToken("token-1");
    }

    @Test
    @DisplayName("콜백: 팔로워 10명 미만이면 등록만 되고 유저 그대로")
    void callback_registered() throws Exception {
        User user = saveUser(Role.USER);
        givenChzzkChannel("channel-1", 9);

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .cookie(cookie(sessionWithState(user, "state-1"))))
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
                        .cookie(cookie(sessionWithState(user, "state-1"))))
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
                        .cookie(cookie(sessionWithState(user, "state-1"))))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=CHANNEL_ALREADY_REGISTERED"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("콜백: 정상 회원이 아니면(탈퇴 등) 치지직을 부르지 않고 실패 화면으로")
    void callback_withdrawn() throws Exception {
        User user = saveUser(Role.USER);
        withdraw(user);

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .cookie(cookie(sessionWithState(user, "state-1"))))
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
                        .cookie(cookie(sessionWithState(user, "state-1"))))
                .andExpect(header().string("Location", "/mypage?chzzk=failed&code=CHZZK_REQUEST_REJECTED"));
    }

    @Test
    @DisplayName("콜백: 치지직 장애여도 토큰은 폐기하고 실패 화면으로 (code=INTERNAL_ERROR)")
    void callback_chzzkFailed() throws Exception {
        User user = saveUser(Role.USER);
        given(chzzkApiClient.issueToken("code-1", "state-1")).willReturn("token-1");
        given(chzzkApiClient.findMe("token-1")).willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        mockMvc.perform(get(CALLBACK).param("code", "code-1").param("state", "state-1")
                        .cookie(cookie(sessionWithState(user, "state-1"))))
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
        Session session = sessionWithState(user, null);

        mockMvc.perform(post(approvalRequest(channel)).cookie(cookie(session)).with(csrf()))
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

    /** POST /chzzk/authorize로 연동을 시작하고, 받은 쿠키(브라우저 ID)와 주소의 state를 돌려준다 */
    private ChzzkStart authorize(User user) throws Exception {
        given(chzzkApiClient.authorizeUrl(any())).willAnswer(call -> "https://chzzk.example/auth?state=" + call.getArgument(0));
        MvcResult result = mockMvc.perform(post(AUTHORIZE).with(TestLogin.as(user.getId(), user.getRole())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizeUrl").isNotEmpty())
                .andReturn();
        String authorizeUrl = JsonPath.read(result.getResponse().getContentAsString(), "$.data.authorizeUrl");
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String browserId = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        createdRedisKeys.add("member:chzzk-state:" + browserId);
        return new ChzzkStart(browserId, authorizeUrl.substring(authorizeUrl.indexOf("state=") + "state=".length()), setCookie);
    }

    private record ChzzkStart(String browserId, String state, String setCookie) {
    }

    private void givenChzzkChannel(String channelId, int followerCount, String state) {
        given(chzzkApiClient.issueToken("code-1", state)).willReturn("token-1");
        given(chzzkApiClient.findMe("token-1")).willReturn(new ChzzkUser(channelId, "방송인"));
        given(chzzkApiClient.findFollowerCount(channelId)).willReturn(followerCount);
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

    /** 운영과 같이 JDBC에 보관한 세션으로 요청하고, 응답 뒤에는 저장소에서 다시 읽는다. */
    private Session sessionWithState(User user, String state) {
        var jdbcSession = sessionRepository.createSession();
        Session session = jdbcSession;
        if (state != null) {
            session.setAttribute(ApiV1MemberChannelController.CHZZK_STATE, state);
        }
        MemberOAuth2User principal = new MemberOAuth2User(user.getId(), user.getRole(), Map.of("id", user.getId()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        sessionRepository.save(jdbcSession);
        return session;
    }

    private Cookie cookie(Session session) {
        // Spring Session DefaultCookieSerializer의 기본 인코딩.
        return new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Session storedSession(Session session) {
        Session stored = sessionRepository.findById(session.getId());
        assertThat(stored).isNotNull();
        return stored;
    }

    private String sessionRole(Session session) {
        SecurityContext context = storedSession(session).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context.getAuthentication().getAuthorities().iterator().next().getAuthority();
    }
}
