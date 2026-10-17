package com.hapbang.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.journey.support.JourneySessions;
import com.hapbang.journey.support.JourneyTest;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.ChzzkApiClient;
import com.hapbang.member.out.ChzzkUser;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

/**
 * 시나리오 1 — 가입·로그인·권한 (#326). 구글 가입이 각 모듈의 복제본·지갑을 만들고, 채널 승인·승급이 저장된 세션 권한과
 * 각 모듈 복제본에 반영되며, 로그아웃하면 세션이 끝나는지를 한 흐름으로 본다.
 * 외부 API는 치지직만 대역이고, 구글 로그인은 OAuth2UserService가 부르는 {@code MemberFacade.oauthLogin}으로 대신한다.
 */
@JourneyTest
class MemberJourneyIntegrationTest {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Long ADMIN_ID = 900_001L;
    private static final String CHANNEL_ID = "journey-chzzk-channel";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberFacade memberFacade;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private BillingUserRepository billingUserRepository;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Autowired
    private AppearanceUserRepository appearanceUserRepository;

    @MockitoBean
    private ChzzkApiClient chzzkApiClient;

    private JourneySessions sessions;

    @BeforeEach
    void setUp() {
        sessions = new JourneySessions(sessionRepository);
    }

    @AfterEach
    void deleteSessions() {
        sessions.deleteAll();
    }

    @Test
    @DisplayName("구글 가입 → 채널 승인(인플루언서) → 승급(검증된 인플루언서) → 로그아웃이 세션·지갑·각 모듈 복제본까지 이어진다")
    void memberJourney() throws Exception {
        // 1) 신규 가입: 커밋 뒤 이벤트로 지갑(0 HP)과 billing·chat·lecture·appearance 복제본이 생긴다
        User user = memberFacade.oauthLogin(OAuthProvider.GOOGLE, "journey-google-sub", "journey@example.com",
                "여정  테스터!!");
        Long userId = user.getId();
        await().atMost(EVENT_TIMEOUT).untilAsserted(() -> {
            assertThat(walletRepository.findByUserId(userId)).hasValueSatisfying(wallet -> {
                assertThat(wallet.getChargeBalanceHp()).isZero();
                assertThat(wallet.getRevenueBalanceHp()).isZero();
            });
            assertThat(billingUserRepository.findById(userId)).isPresent();
            assertThat(chatUserRepository.findById(userId)).hasValueSatisfying(chatUser -> {
                assertThat(chatUser.getRole()).isEqualTo(Role.USER);
                assertThat(chatUser.canChat()).isFalse();
            });
            assertThat(lectureUserRepository.findById(userId)).hasValueSatisfying(
                    lectureUser -> assertThat(lectureUser.getRole()).isEqualTo(Role.USER));
            assertThat(appearanceUserRepository.findById(userId)).hasValueSatisfying(
                    appearanceUser -> assertThat(appearanceUser.getRole()).isEqualTo(Role.USER));
        });

        // 2) 로그인한 세션으로 내 정보: 역할 USER, 닉네임은 구글 이름 다듬기 규칙(특수문자 제거, 연속 공백 하나로)
        String sessionId = sessions.login(userId, Role.USER);
        Cookie cookie = JourneySessions.cookie(sessionId);
        mockMvc.perform(get("/api/v1/members/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.nickname").value("여정 테스터"));

        // 3) 치지직 채널 등록(팔로워 3명 → 등록만) → 승인 요청(심사 대기) → 관리자 승인
        given(chzzkApiClient.authorizeUrl(anyString())).willReturn("https://chzzk.example/authorize");
        given(chzzkApiClient.issueToken(anyString(), anyString())).willReturn("journey-token");
        given(chzzkApiClient.findMe("journey-token")).willReturn(new ChzzkUser(CHANNEL_ID, "여정 채널"));
        given(chzzkApiClient.findFollowerCount(CHANNEL_ID)).willReturn(3);

        mockMvc.perform(get("/api/v1/members/channels/chzzk/connect").cookie(cookie))
                .andExpect(status().is3xxRedirection());
        String state = sessions.attributeOf(sessionId, "CHZZK_STATE");
        assertThat(state).isNotBlank();
        mockMvc.perform(get("/api/v1/members/channels/chzzk/callback")
                        .param("code", "journey-code").param("state", state).cookie(cookie))
                .andExpect(redirectedUrl("/mypage?chzzk=registered"));

        String channels = mockMvc.perform(get("/api/v1/members/channels/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("REGISTERED"))
                .andReturn().getResponse().getContentAsString();
        Long channelId = ((Number) JsonPath.read(channels, "$.data[0].id")).longValue();

        mockMvc.perform(post("/api/v1/members/channels/{id}/approval-request", channelId).cookie(cookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.role").value("USER"));

        mockMvc.perform(post("/api/v1/members/admin/channels/{id}/approve", channelId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());

        // 승인 결과: DB 역할, 저장된 세션 권한(같은 쿠키로 바로 인플루언서), 각 모듈 복제본
        mockMvc.perform(get("/api/v1/members/me").cookie(cookie))
                .andExpect(jsonPath("$.data.role").value("INFLUENCER"));
        assertThat(sessions.roleOf(sessionId)).isEqualTo(Role.INFLUENCER);
        await().atMost(EVENT_TIMEOUT).untilAsserted(() -> {
            assertThat(chatUserRepository.findById(userId)).hasValueSatisfying(
                    chatUser -> assertThat(chatUser.canChat()).isTrue());
            assertThat(appearanceUserRepository.findById(userId)).hasValueSatisfying(
                    appearanceUser -> assertThat(appearanceUser.getRole()).isEqualTo(Role.INFLUENCER));
            assertThat(lectureUserRepository.findById(userId)).hasValueSatisfying(
                    lectureUser -> assertThat(lectureUser.getRole()).isEqualTo(Role.INFLUENCER));
        });

        // 4) 승급 신청 → 관리자 승인 → 검증된 인플루언서. 강의 판매자 API가 같은 세션으로 열린다
        mockMvc.perform(get("/api/v1/lectures/me").cookie(cookie))
                .andExpect(status().isForbidden());
        String upgrade = mockMvc.perform(post("/api/v1/members/upgrade-requests").cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "방송 노하우 강의를 열고 싶어요"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        Long upgradeId = ((Number) JsonPath.read(upgrade, "$.data.id")).longValue();

        mockMvc.perform(post("/api/v1/members/admin/upgrade-requests/{id}/approve", upgradeId)
                        .with(TestLogin.as(ADMIN_ID, Role.ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/members/me").cookie(cookie))
                .andExpect(jsonPath("$.data.role").value("VERIFIED_INFLUENCER"));
        assertThat(sessions.roleOf(sessionId)).isEqualTo(Role.VERIFIED_INFLUENCER);
        await().atMost(EVENT_TIMEOUT).untilAsserted(() -> assertThat(lectureUserRepository.findById(userId))
                .hasValueSatisfying(lectureUser -> assertThat(lectureUser.getRole())
                        .isEqualTo(Role.VERIFIED_INFLUENCER)));
        mockMvc.perform(get("/api/v1/lectures/me").cookie(cookie))
                .andExpect(status().isOk());

        // 5) 로그아웃: 세션이 지워지고 같은 쿠키로는 401
        mockMvc.perform(post("/api/v1/members/logout").cookie(cookie).with(csrf()))
                .andExpect(status().isOk());
        assertThat(sessions.exists(sessionId)).isFalse();
        mockMvc.perform(get("/api/v1/members/me").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }
}
