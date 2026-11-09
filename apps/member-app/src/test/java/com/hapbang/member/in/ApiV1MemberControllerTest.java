package com.hapbang.member.in;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.GatewayLogin;
import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.app.MemberAppearanceRatingFacade;
import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.appearance.dto.AppearanceParticipantRole;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

import jakarta.servlet.http.Cookie;

@MemberIntegrationTest
@AutoConfigureMockMvc
class ApiV1MemberControllerTest {

    private static final String NICKNAME_AVAILABILITY = "/api/v1/members/nicknames/availability";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    InfluencerChannelRepository influencerChannelRepository;

    @Autowired
    MemberAppearanceRatingFacade memberAppearanceRatingFacade;

    private static final AtomicLong REVIEW_ID = new AtomicLong(1);

    @Test
    @DisplayName("로그인 상태로 내 정보를 조회하면 200과 회원 정보를 반환한다")
    void me_loggedIn() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(get("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("내 정보를 조회했습니다."))
                .andExpect(jsonPath("$.data.id").value(user.getId()))
                .andExpect(jsonPath("$.data.email").value("tester1@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("tester1"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                // 콘텐츠 분야는 없어도 빈 배열, 자기소개는 null (#711)
                .andExpect(jsonPath("$.data.contentCategories").isArray())
                .andExpect(jsonPath("$.data.contentCategories.length()").value(0))
                .andExpect(jsonPath("$.data.bio").hasJsonPath())
                .andExpect(jsonPath("$.data.bio").value(nullValue()));
    }

    @Test
    @DisplayName("내 정보에 고른 순서의 콘텐츠 분야(코드·이름)와 자기소개를 준다 (#711)")
    void me_contentCategoriesAndBio() throws Exception {
        User user = saveContentProfile("tester1", List.of("MAPLESTORY", "GAME"), "메이플 위주");

        mockMvc.perform(get("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentCategories.length()").value(2))
                .andExpect(jsonPath("$.data.contentCategories[0].code").value("MAPLESTORY"))
                .andExpect(jsonPath("$.data.contentCategories[0].name").value("메이플스토리"))
                .andExpect(jsonPath("$.data.contentCategories[1].code").value("GAME"))
                .andExpect(jsonPath("$.data.contentCategories[1].name").value("게임"))
                .andExpect(jsonPath("$.data.bio").value("메이플 위주"));
    }

    @Test
    @DisplayName("비로그인으로 내 정보를 조회하면 구글로 리다이렉트하지 않고 401을 반환한다")
    void me_notLoggedIn() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("쿼리 로그인(local-login)은 없어졌다: 로그인 없이는 401, 로그인해도 404 (#520)")
    void localLogin_removed() throws Exception {
        mockMvc.perform(post("/api/v1/members/local-login?role=ADMIN"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/members/local-login?role=ADMIN").with(GatewayLogin.as(1L, Role.USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("닉네임을 수정하면 200과 수정된 내 정보를 반환하고 DB에도 저장된다")
    void updateMe() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("새 닉네임"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("새 닉네임");
    }

    @Test
    @DisplayName("다른 회원(탈퇴 회원 포함)이 쓰는 닉네임이면 대소문자만 달라도 409 DUPLICATE_NICKNAME (#646)")
    void updateMe_duplicateNickname() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));
        userRepository.save(User.join("tester2@example.com", "Tester2"));
        User withdrawn = User.join("tester3@example.com", "tester3");
        ReflectionTestUtils.setField(withdrawn, "status", UserStatus.WITHDRAWN);
        userRepository.save(withdrawn);

        for (String nickname : new String[] {"tester2", "TESTER3"}) {
            mockMvc.perform(patch("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"%s\"}".formatted(nickname)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
        }
        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("tester1");
    }

    @Test
    @DisplayName("내 닉네임의 대소문자만 바꾸면 200 (#646)")
    void updateMe_ownNicknameCase() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"TESTER1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("TESTER1"));
    }

    @Test
    @DisplayName("인플루언서가 콘텐츠 분야·자기소개를 보내면 200이고 저장된다 (#711)")
    void updateMe_contentCategoriesAndBio() throws Exception {
        User user = User.join("tester1@example.com", "tester1");
        user.changeRole(Role.INFLUENCER);
        userRepository.save(user);

        mockMvc.perform(patch("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentCategories\":[\"MAPLESTORY\",\"GAME\"],\"bio\":\" 메이플 위주 \"}"))
                .andExpect(status().isOk());

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getContentCategories()).containsExactly("MAPLESTORY", "GAME");
        assertThat(saved.getBio()).isEqualTo("메이플 위주");
    }

    @Test
    @DisplayName("유저가 비어 있지 않은 콘텐츠 분야를 보내면 403 INFLUENCER_PROFILE_NOT_ALLOWED이고 닉네임도 바뀌지 않는다 (#711)")
    void updateMe_contentCategoriesByUser() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\",\"contentCategories\":[\"GAME\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INFLUENCER_PROFILE_NOT_ALLOWED"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("tester1");
    }

    @Test
    @DisplayName("닉네임 중복 확인: 로그인 없이 부르고, 아무도 안 쓰면 true (#646)")
    void nicknameAvailability_available() throws Exception {
        mockMvc.perform(get(NICKNAME_AVAILABILITY).param("nickname", " 새닉네임 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    @DisplayName("닉네임 중복 확인: 다른 회원(탈퇴 회원 포함)이 쓰면 대소문자만 달라도 false (#646)")
    void nicknameAvailability_taken() throws Exception {
        userRepository.save(User.join("tester2@example.com", "Tester2"));
        User withdrawn = User.join("tester3@example.com", "tester3");
        ReflectionTestUtils.setField(withdrawn, "status", UserStatus.WITHDRAWN);
        userRepository.save(withdrawn);

        for (String nickname : new String[] {"tester2", "TESTER3"}) {
            mockMvc.perform(get(NICKNAME_AVAILABILITY).param("nickname", nickname))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.available").value(false));
        }
    }

    @Test
    @DisplayName("닉네임 중복 확인: 로그인한 회원이 자기 닉네임을 넣으면 true, 남의 닉네임이면 false (#646)")
    void nicknameAvailability_loggedIn() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));
        userRepository.save(User.join("tester2@example.com", "tester2"));

        mockMvc.perform(get(NICKNAME_AVAILABILITY).param("nickname", "TESTER1")
                        .with(GatewayLogin.as(user.getId(), user.getRole())))
                .andExpect(jsonPath("$.data.available").value(true));
        mockMvc.perform(get(NICKNAME_AVAILABILITY).param("nickname", "tester2")
                        .with(GatewayLogin.as(user.getId(), user.getRole())))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    @DisplayName("닉네임 중복 확인: 규칙에 맞지 않거나 없으면 400 닉네임 오류 (#646)")
    void nicknameAvailability_invalid() throws Exception {
        mockMvc.perform(get(NICKNAME_AVAILABILITY).param("nickname", "민"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NICKNAME_LENGTH"));
        mockMvc.perform(get(NICKNAME_AVAILABILITY).param("nickname", "민규!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NICKNAME_CHARACTER"));
        mockMvc.perform(get(NICKNAME_AVAILABILITY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NICKNAME_LENGTH"));
    }

    @Test
    @DisplayName("닉네임 없이 수정하면 200이고 닉네임은 그대로다")
    void updateMe_withoutNickname() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("tester1"));
    }

    @Test
    @DisplayName("규칙에 맞지 않는 닉네임이면 400과 오류 코드를 ProblemDetail로 반환한다")
    void updateMe_invalidNickname() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())).with(csrf())
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
    @DisplayName("CSRF 토큰 없이도 수정할 수 있다 (쿠키가 아니라 Gateway 사용자 헤더로 인증, #370)")
    void updateMe_withoutCsrfToken() throws Exception {
        User user = userRepository.save(User.join("tester1@example.com", "tester1"));

        mockMvc.perform(patch("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("없는 회원으로 내 정보를 조회하면 404와 MEMBER_NOT_FOUND를 반환한다")
    void me_memberNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/members/me").with(GatewayLogin.as(999L, Role.USER)))
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

        mockMvc.perform(get("/api/v1/members/influencers").with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
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
    @DisplayName("인플루언서 목록에 콘텐츠 분야(코드·이름)를 주고, 자기소개는 넣지 않는다 (#711)")
    void influencers_contentCategories() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        saveContentProfile("target1", List.of("MUKBANG"), "먹방 위주");

        mockMvc.perform(get("/api/v1/members/influencers").with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].contentCategories[0].code").value("MUKBANG"))
                .andExpect(jsonPath("$.data.content[0].contentCategories[0].name").value("먹방"))
                .andExpect(jsonPath("$.data.content[0].bio").doesNotExist());
    }

    @Test
    @DisplayName("페이지 크기가 최대(100)를 넘으면 100으로 맞춘다")
    void influencers_maxSize() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/members/influencers?size=200").with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
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
                        .with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
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

        mockMvc.perform(get("/api/v1/members/{memberId}", target.getId()).with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
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
    @DisplayName("회원 프로필에 콘텐츠 분야(코드·이름)와 자기소개를 주고, 목록에서 꺼진 분야도 이름과 함께 그대로 보인다 (#711, Q50)")
    void profile_contentCategoriesAndBio() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User target = saveContentProfile("target1", List.of("GAME", "MUKBANG"), "먹방 위주");
        jdbcTemplate.update("update members.content_category set active = false where code = 'GAME'");

        mockMvc.perform(get("/api/v1/members/{memberId}", target.getId()).with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentCategories[0].code").value("GAME"))
                .andExpect(jsonPath("$.data.contentCategories[0].name").value("게임"))
                .andExpect(jsonPath("$.data.contentCategories[1].code").value("MUKBANG"))
                .andExpect(jsonPath("$.data.bio").value("먹방 위주"));
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

        mockMvc.perform(get("/api/v1/members/{memberId}", target.getId()).with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
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

        mockMvc.perform(get("/api/v1/members/{memberId}", withdrawn.getId()).with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 회원의 프로필은 404와 MEMBER_NOT_FOUND를 반환한다")
    void profile_notFound() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/members/{memberId}", 9999L).with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    @DisplayName("회원 프로필에는 출연 평점을 전체와 받은 역할별로 넣는다 (#555)")
    void profile_appearanceRating() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User target = saveMember("target1", Role.INFLUENCER, UserStatus.ACTIVE);
        applyReview(target, AppearanceParticipantRole.HOST, 5);
        applyReview(target, AppearanceParticipantRole.HOST, 4);
        applyReview(target, AppearanceParticipantRole.GUEST, 3);

        mockMvc.perform(get("/api/v1/members/{memberId}", target.getId()).with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appearanceRating.total.average").value(4.0))
                .andExpect(jsonPath("$.data.appearanceRating.total.count").value(3))
                .andExpect(jsonPath("$.data.appearanceRating.asHost.average").value(4.5))
                .andExpect(jsonPath("$.data.appearanceRating.asHost.count").value(2))
                .andExpect(jsonPath("$.data.appearanceRating.asGuest.average").value(3.0))
                .andExpect(jsonPath("$.data.appearanceRating.asGuest.count").value(1));
    }

    @Test
    @DisplayName("받은 출연 리뷰가 없으면 출연 평점은 평균 null, 개수 0이다 (#555)")
    void profile_noAppearanceRating() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User target = saveMember("target1", Role.INFLUENCER, UserStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/members/{memberId}", target.getId()).with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appearanceRating.total.average").value(nullValue()))
                .andExpect(jsonPath("$.data.appearanceRating.total.count").value(0))
                .andExpect(jsonPath("$.data.appearanceRating.asHost.count").value(0))
                .andExpect(jsonPath("$.data.appearanceRating.asGuest.count").value(0));
    }

    @Test
    @DisplayName("인플루언서 목록에는 출연 평점을 전체만 넣는다 (#555)")
    void influencers_appearanceRating() throws Exception {
        User viewer = saveMember("viewer1", Role.USER, UserStatus.ACTIVE);
        User unrated = saveMember("unrated1", Role.INFLUENCER, UserStatus.ACTIVE);
        User rated = saveMember("rated1", Role.INFLUENCER, UserStatus.ACTIVE);
        applyReview(rated, AppearanceParticipantRole.HOST, 5);
        applyReview(rated, AppearanceParticipantRole.GUEST, 4);
        applyReview(rated, AppearanceParticipantRole.GUEST, 4);

        mockMvc.perform(get("/api/v1/members/influencers").with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(rated.getId()))
                .andExpect(jsonPath("$.data.content[0].appearanceRating.average").value(4.3))
                .andExpect(jsonPath("$.data.content[0].appearanceRating.count").value(3))
                .andExpect(jsonPath("$.data.content[0].appearanceRating.asHost").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].id").value(unrated.getId()))
                .andExpect(jsonPath("$.data.content[1].appearanceRating.average").value(nullValue()))
                .andExpect(jsonPath("$.data.content[1].appearanceRating.count").value(0));
    }

    private void applyReview(User reviewee, AppearanceParticipantRole role, int rating) {
        long reviewId = REVIEW_ID.getAndIncrement();
        memberAppearanceRatingFacade.applyReviewPublished(new AppearanceReviewPublishedEvent(reviewId, reviewId, 999L,
                reviewee.getId(), role, rating, LocalDateTime.now()));
    }

    @Test
    @DisplayName("탈퇴 기능 스위치가 꺼져 있으면 탈퇴 API는 404이고 회원은 그대로다 (#593)")
    void withdraw_disabled() throws Exception {
        User user = saveMember("tester1", Role.USER, UserStatus.ACTIVE);

        mockMvc.perform(delete("/api/v1/members/me").with(GatewayLogin.as(user.getId(), user.getRole())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("탈퇴 기능 스위치가 꺼져 있으면 복구 티켓이 없으므로 복구 API는 401 INVALID_RESTORE_TICKET (#593)")
    void restore_disabled() throws Exception {
        mockMvc.perform(post("/api/v1/members/restore")
                        .cookie(new Cookie(RestoreTicketCookies.NAME, "any")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_RESTORE_TICKET"));
    }

    /** 탈퇴 기능(F7)이 아직 없어 상태는 테스트에서만 직접 바꾼다 */
    /** 분야·자기소개를 고른 정상 인플루언서 */
    private User saveContentProfile(String nickname, List<String> contentCategories, String bio) {
        User user = User.join(nickname + "@example.com", nickname);
        user.changeRole(Role.INFLUENCER);
        user.changeContentCategories(contentCategories);
        user.changeBio(bio);
        return userRepository.saveAndFlush(user);
    }

    private User saveMember(String nickname, Role role, UserStatus status) {
        User user = User.join(nickname + "@example.com", nickname);
        user.changeRole(role);
        ReflectionTestUtils.setField(user, "status", status);
        return userRepository.saveAndFlush(user);
    }
}
