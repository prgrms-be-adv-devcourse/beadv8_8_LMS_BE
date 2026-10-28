package com.hapbang.member.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.TestLogin;

@IntegrationTest
@AutoConfigureMockMvc
class ApiV1MemberControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Test
    @DisplayName("로그인 상태로 내 정보를 조회하면 200과 회원 정보를 반환한다")
    void me_loggedIn() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(get("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("내 정보를 조회했습니다."))
                .andExpect(jsonPath("$.data.id").value(user.getId()))
                .andExpect(jsonPath("$.data.email").value("tester1@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("tester1"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("비로그인으로 내 정보를 조회하면 구글로 리다이렉트하지 않고 401을 반환한다")
    void me_notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃하면 200을 반환하고 세션 쿠키를 지운다")
    void logout() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(post("/api/v1/members/logout").with(TestLogin.as(user.getId(), user.getRole())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("SESSION", 0));
    }

    @Test
    @DisplayName("CSRF 토큰 없이 로그아웃하면 403을 반환한다")
    void logout_withoutCsrfToken() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(post("/api/v1/members/logout").with(TestLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("로컬 로그인 설정이 꺼져 있으면(서버) 로컬 로그인 주소가 없다")
    void localLogin_disabled() throws Exception {
        mockMvc.perform(post("/api/v1/members/local-login?role=ADMIN").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("닉네임을 수정하면 200과 수정된 내 정보를 반환하고 DB에도 저장된다")
    void updateMe() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("새 닉네임"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("새 닉네임");
    }

    @Test
    @DisplayName("닉네임 없이 수정하면 200이고 닉네임은 그대로다")
    void updateMe_withoutNickname() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("tester1"));
    }

    @Test
    @DisplayName("규칙에 맞지 않는 닉네임이면 400과 오류 코드를 ProblemDetail로 반환한다")
    void updateMe_invalidNickname() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"민\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NICKNAME_LENGTH"))
                .andExpect(jsonPath("$.detail").value("닉네임은 2~12자로 입력해 주세요."));
    }

    @Test
    @DisplayName("비로그인으로 수정하면 401을 반환한다")
    void updateMe_notLoggedIn() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF 토큰 없이 수정하면 403을 반환한다")
    void updateMe_withoutCsrfToken() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(TestLogin.as(user.getId(), user.getRole()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("없는 회원으로 내 정보를 조회하면 404와 MEMBER_NOT_FOUND를 반환한다")
    void me_memberNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/members/me").with(TestLogin.as(999L, Role.USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    @DisplayName("인플루언서 목록은 인플루언서만 최신 가입순으로 주고 이메일은 넣지 않는다")
    void influencers() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User older = saveMember("older1", Role.INFLUENCER, UserStatus.ACTIVE);
        User newer = saveMember("newer1", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE);
        saveMember("admin1", Role.ADMIN, UserStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/members/influencers").with(TestLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(newer.getId()))
                .andExpect(jsonPath("$.data.content[1].id").value(older.getId()))
                .andExpect(jsonPath("$.data.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.page.totalPages").value(1));
    }

    @Test
    @DisplayName("페이지 크기가 최대(100)를 넘으면 100으로 맞춘다")
    void influencers_maxSize() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/members/influencers?size=200").with(TestLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.size").value(100));
    }

    @Test
    @DisplayName("정렬을 요청해도 인플루언서 목록은 최신 가입순으로 준다")
    void influencers_fixedSort() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User older = saveMember("aaa1", Role.INFLUENCER, UserStatus.ACTIVE);
        User newer = saveMember("zzz1", Role.INFLUENCER, UserStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/members/influencers?sort=nickname,asc")
                        .with(TestLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(newer.getId()))
                .andExpect(jsonPath("$.data.content[1].id").value(older.getId()));
    }

    @Test
    @DisplayName("비로그인으로 인플루언서 목록을 보면 401을 반환한다")
    void influencers_notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/v1/members/influencers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("회원 프로필은 공개 정보(id·닉네임·역할)만 주고 이메일은 넣지 않는다")
    void profile() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User target = saveMember("target1", Role.INFLUENCER, UserStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/members/{memberId}", target.getId()).with(TestLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(target.getId()))
                .andExpect(jsonPath("$.data.nickname").value("target1"))
                .andExpect(jsonPath("$.data.role").value("INFLUENCER"))
                .andExpect(jsonPath("$.data.email").doesNotExist())
                // S3 공통 설정 전이라 사진 자리는 있고 값은 null
                .andExpect(jsonPath("$.data.profileImageUrl").hasJsonPath())
                .andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));
    }

    @Test
    @DisplayName("회원 프로필에는 등록된 채널을 상태와 상관없이 모두 넣고, 상태·반려 사유는 넣지 않는다")
    void profile_channels() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User target = saveMember("target1", Role.INFLUENCER, UserStatus.ACTIVE);
        influencerChannelRepository.save(InfluencerChannel.register(target, ChannelPlatform.CHZZK, "channel-1",
                "본채널", 20, LocalDateTime.now()));
        influencerChannelRepository.save(InfluencerChannel.register(target, ChannelPlatform.CHZZK, "channel-2",
                "서브채널", 3, LocalDateTime.now()));

        mockMvc.perform(get("/api/v1/members/{memberId}", target.getId()).with(TestLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channels.length()").value(2))
                .andExpect(jsonPath("$.data.channels[0].channelName").value("본채널"))
                .andExpect(jsonPath("$.data.channels[0].followerCount").value(20))
                .andExpect(jsonPath("$.data.channels[1].channelName").value("서브채널"))
                .andExpect(jsonPath("$.data.channels[1].channelUrl").value("https://chzzk.naver.com/channel-2"))
                .andExpect(jsonPath("$.data.channels[0].status").doesNotExist())
                .andExpect(jsonPath("$.data.channels[0].rejectionReason").doesNotExist());
    }

    @Test
    @DisplayName("탈퇴한 회원의 프로필은 404와 MEMBER_NOT_FOUND를 반환한다")
    void profile_withdrawn() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User withdrawn = saveMember("withdrawn1", Role.INFLUENCER, UserStatus.WITHDRAWN);

        mockMvc.perform(get("/api/v1/members/{memberId}", withdrawn.getId()).with(TestLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 회원의 프로필은 404와 MEMBER_NOT_FOUND를 반환한다")
    void profile_notFound() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/members/{memberId}", 9999L).with(TestLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    /** 탈퇴 기능(F7)이 아직 없어 상태는 테스트에서만 직접 바꾼다 */
    private User saveMember(String nickname, Role role, UserStatus status) {
        User user = User.join(nickname + "@example.com", nickname);
        user.changeRole(role);
        ReflectionTestUtils.setField(user, "status", status);
        return userRepository.saveAndFlush(user);
    }
}
