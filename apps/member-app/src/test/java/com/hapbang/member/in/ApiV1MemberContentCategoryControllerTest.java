package com.hapbang.member.in;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.hapbang.member.GatewayLogin;
import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 콘텐츠 분야 목록 API (#711) */
@MemberIntegrationTest
@AutoConfigureMockMvc
class ApiV1MemberContentCategoryControllerTest {

    private static final String CONTENT_CATEGORIES = "/api/v1/members/content-categories";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("정상 인플루언서가 많이 고른 순으로 주고, 유저·탈퇴 회원이 고른 것은 세지 않는다")
    void orderByCount() throws Exception {
        save("inf1", Role.INFLUENCER, UserStatus.ACTIVE, List.of("MUKBANG", "GAME"));
        save("inf2", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, List.of("MUKBANG"));
        // 탈퇴·강등된 회원도 분야는 남아 있지만 세지 않는다
        save("gone1", Role.INFLUENCER, UserStatus.WITHDRAWN, List.of("TRAVEL", "COOKING"));
        save("gone2", Role.INFLUENCER, UserStatus.WITHDRAWN, List.of("TRAVEL", "COOKING"));
        User viewer = save("user1", Role.USER, UserStatus.ACTIVE, List.of());

        mockMvc.perform(get(CONTENT_CATEGORIES).with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(23))
                .andExpect(jsonPath("$.data[0].code").value("MUKBANG"))
                .andExpect(jsonPath("$.data[0].name").value("먹방"))
                .andExpect(jsonPath("$.data[1].code").value("GAME"))
                // 나머지는 아무도 고르지 않아 이름순(영문으로 시작하는 이름이 한글보다 앞)
                .andExpect(jsonPath("$.data[2].name").value("FC 온라인"))
                .andExpect(jsonPath("$.data[3].name").value("교육·지식"))
                .andExpect(jsonPath("$.data[4].name").value("그림"))
                .andExpect(jsonPath("$.data[22].name").value("저스트채팅"));
    }

    @Test
    @DisplayName("목록에서 꺼진 분야는 주지 않는다 (Q50)")
    void excludesTurnedOff() throws Exception {
        save("inf1", Role.INFLUENCER, UserStatus.ACTIVE, List.of("GAME"));
        jdbcTemplate.update("update members.content_category set active = false where code = 'GAME'");
        User viewer = save("user1", Role.USER, UserStatus.ACTIVE, List.of());

        mockMvc.perform(get(CONTENT_CATEGORIES).with(GatewayLogin.as(viewer.getId(), viewer.getRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(22))
                .andExpect(jsonPath("$.data[?(@.code == 'GAME')]").isEmpty());
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void notLoggedIn() throws Exception {
        mockMvc.perform(get(CONTENT_CATEGORIES))
                .andExpect(status().isUnauthorized());
    }

    /** 분야는 인플루언서일 때 골라 두고, 그 뒤 역할·상태를 바꾼다(강등·탈퇴해도 분야는 남는다) */
    private User save(String nickname, Role role, UserStatus status, List<String> contentCategories) {
        User user = User.join(nickname + "@example.com", nickname);
        user.changeContentCategories(contentCategories);
        user.changeRole(role);
        ReflectionTestUtils.setField(user, "status", status);
        return userRepository.saveAndFlush(user);
    }
}
