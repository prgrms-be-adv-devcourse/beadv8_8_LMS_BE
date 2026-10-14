package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.global.storage.S3FileStorage;
import com.hapbang.member.domain.ProfileImage;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.S3MockTestcontainersConfig;
import com.hapbang.testsupport.TestLogin;

/** 업로드(서버가 S3Mock에 저장) → 지정 → 조회 URL을 HTTP부터 S3까지 확인한다. 형식·크기 경계값은 ProfileImageTest가 맡는다 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(S3MockTestcontainersConfig.class)
class ProfileImageApiTest {

    private static final byte[] IMAGE = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    S3FileStorage s3FileStorage;

    @Test
    @DisplayName("이미지를 올리면 S3에 저장하고 바로 프로필 사진으로 지정해 조회 URL을 준다")
    void upload() throws Exception {
        User user = userRepository.save(MemberFixture.user());

        mockMvc.perform(upload(user, user.getId(), "image/png", IMAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("프로필 사진을 바꿨습니다."))
                .andExpect(jsonPath("$.data.profileImageUrl").value(Matchers.containsString("member/profile/" + user.getId() + "/")));

        String key = userRepository.findById(user.getId()).orElseThrow().getProfileImageKey();
        assertThat(key).startsWith("member/profile/" + user.getId() + "/");
        assertThat(s3FileStorage.find(key)).hasValueSatisfying(file -> {
            assertThat(file.size()).isEqualTo(IMAGE.length);
            assertThat(file.contentType()).isEqualTo("image/png");
        });
    }

    @Test
    @DisplayName("올린 사진은 내 정보·회원 프로필·인플루언서 목록에 조회 URL로 나온다")
    void urlInResponses() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        mockMvc.perform(upload(influencer, influencer.getId(), "image/webp", IMAGE)).andExpect(status().isOk());
        User viewer = userRepository.save(MemberFixture.user());

        mockMvc.perform(get("/api/v1/members/me").with(TestLogin.as(influencer.getId(), influencer.getRole())))
                .andExpect(jsonPath("$.data.profileImageUrl").value(Matchers.notNullValue()));
        mockMvc.perform(get("/api/v1/members/{memberId}", influencer.getId()).with(TestLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(jsonPath("$.data.profileImageUrl").value(Matchers.notNullValue()));
        mockMvc.perform(get("/api/v1/members/influencers").with(TestLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(jsonPath("$.data.content[0].profileImageUrl").value(Matchers.notNullValue()));
    }

    @Test
    @DisplayName("남의 회원 ID로 올리면 403이고 사진은 바뀌지 않는다")
    void upload_othersId() throws Exception {
        User owner = userRepository.save(MemberFixture.user());
        User attacker = userRepository.save(MemberFixture.user());

        mockMvc.perform(upload(attacker, owner.getId(), "image/png", IMAGE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROFILE_IMAGE_FORBIDDEN"));

        assertThat(userRepository.findById(owner.getId()).orElseThrow().getProfileImageKey()).isNull();
    }

    @Test
    @DisplayName("허용하지 않는 형식이면 400 INVALID_PROFILE_IMAGE_TYPE")
    void upload_invalidType() throws Exception {
        User user = userRepository.save(MemberFixture.user());

        mockMvc.perform(upload(user, user.getId(), "image/gif", IMAGE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROFILE_IMAGE_TYPE"));
    }

    @Test
    @DisplayName("5MB를 넘으면 400 INVALID_PROFILE_IMAGE_SIZE")
    void upload_tooLarge() throws Exception {
        User user = userRepository.save(MemberFixture.user());

        mockMvc.perform(upload(user, user.getId(), "image/jpeg", new byte[(int) ProfileImage.MAX_SIZE + 1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROFILE_IMAGE_SIZE"));
    }

    @Test
    @DisplayName("프로필 사진을 삭제하면 연결이 해제되고 조회 URL이 null이 된다")
    void delete_profileImage() throws Exception {
        User user = userRepository.save(MemberFixture.user());
        mockMvc.perform(upload(user, user.getId(), "image/png", IMAGE)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/members/me/profile-image")
                        .with(TestLogin.as(user.getId(), user.getRole())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("프로필 사진을 삭제했습니다."))
                .andExpect(jsonPath("$.data.profileImageUrl").value(Matchers.nullValue()));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getProfileImageKey()).isNull();
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void upload_notLoggedIn() throws Exception {
        mockMvc.perform(put("/api/v1/members/files/1/content").contentType("image/png").content(IMAGE).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder upload(User loginUser, Long memberId, String contentType, byte[] body) {
        return put("/api/v1/members/files/{memberId}/content", memberId)
                .contentType(contentType)
                .content(body)
                .with(TestLogin.as(loginUser.getId(), loginUser.getRole()))
                .with(csrf());
    }
}
