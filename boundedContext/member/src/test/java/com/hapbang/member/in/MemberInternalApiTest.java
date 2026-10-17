package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.TestLogin;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

/** 회원 내부 API (#303): 내부 키로만 부르고, 모든 회원을 ID 오름차순 키셋 페이지로 준다 */
@IntegrationTest
@AutoConfigureMockMvc
class MemberInternalApiTest {

    private static final String MEMBERS = "/api/v1/internal/members";
    /** 응답에서 빠진(null) 필드를 읽으면 예외 대신 null */
    private static final Configuration LENIENT = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("내부 키로 첫 페이지를 조회하면 앞의 size명과 다음 커서를 RsData로 준다")
    void firstPage() throws Exception {
        List<User> users = saveMembers(3);

        mockMvc.perform(internal(get(MEMBERS).param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(users.get(0).getId()))
                .andExpect(jsonPath("$.data.items[0].nickname").value(users.get(0).getNickname()))
                .andExpect(jsonPath("$.data.items[0].role").value("USER"))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].version").value(0))
                .andExpect(jsonPath("$.data.items[1].id").value(users.get(1).getId()))
                .andExpect(jsonPath("$.data.nextAfterId").value(users.get(1).getId()));
    }

    @Test
    @DisplayName("afterId를 넘기면 그 뒤 회원부터 주고, 마지막 페이지면 nextAfterId가 없다")
    void nextPage() throws Exception {
        List<User> users = saveMembers(3);

        mockMvc.perform(internal(get(MEMBERS).param("afterId", users.get(1).getId().toString())
                        .param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(users.get(2).getId()))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist());
    }

    @Test
    @DisplayName("nextAfterId로 끝까지 넘기면 모든 회원이 중복·누락 없이 ID 순서대로 나온다")
    void walkAllPages() throws Exception {
        List<Long> expected = saveMembers(5).stream().map(User::getId).toList();

        List<Long> collected = new ArrayList<>();
        Long afterId = null;
        do {
            MockHttpServletRequestBuilder request = get(MEMBERS).param("size", "2");
            if (afterId != null) {
                request.param("afterId", afterId.toString());
            }
            DocumentContext body = JsonPath.using(LENIENT).parse(
                    mockMvc.perform(internal(request)).andReturn().getResponse().getContentAsString());
            List<Number> ids = body.read("$.data.items[*].id");
            ids.forEach(id -> collected.add(id.longValue()));
            Number next = body.read("$.data.nextAfterId");
            afterId = next == null ? null : next.longValue();
        } while (afterId != null);

        assertThat(collected).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("모든 역할과 정지·탈퇴 회원도 포함하고, version은 회원 원본 값이다")
    void allRolesAndStatuses() throws Exception {
        User admin = userRepository.save(MemberFixture.member(Role.ADMIN));
        User verified = userRepository.save(MemberFixture.member(Role.VERIFIED_INFLUENCER));
        User suspended = userRepository.save(MemberFixture.member(Role.INFLUENCER, UserStatus.SUSPENDED));
        User withdrawn = userRepository.save(MemberFixture.member(Role.USER, UserStatus.WITHDRAWN));
        withdrawn.changeNickname("바뀐닉네임");
        userRepository.saveAndFlush(withdrawn);

        mockMvc.perform(internal(get(MEMBERS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[0].id").value(admin.getId()))
                .andExpect(jsonPath("$.data.items[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data.items[1].role").value("VERIFIED_INFLUENCER"))
                .andExpect(jsonPath("$.data.items[2].id").value(suspended.getId()))
                .andExpect(jsonPath("$.data.items[2].status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.items[3].status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data.items[3].nickname").value("바뀐닉네임"))
                .andExpect(jsonPath("$.data.items[3].version").value(1))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist());
        assertThat(verified.getId()).isGreaterThan(admin.getId());
    }

    @Test
    @DisplayName("마지막 회원 뒤로 조회하면 빈 목록이고 nextAfterId가 없다")
    void emptyPage() throws Exception {
        List<User> users = saveMembers(1);

        mockMvc.perform(internal(get(MEMBERS).param("afterId", users.getFirst().getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist());
    }

    @Test
    @DisplayName("size를 생략하면 100명까지 준다")
    void defaultSize() throws Exception {
        saveMembers(101);

        mockMvc.perform(internal(get(MEMBERS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(100))
                .andExpect(jsonPath("$.data.nextAfterId").exists());
    }

    @Test
    @DisplayName("size가 1~500 밖이거나 afterId가 음수면 400 INVALID_REQUEST")
    void invalidRequest() throws Exception {
        for (String[] param : new String[][] {{"size", "0"}, {"size", "501"}, {"afterId", "-1"}}) {
            mockMvc.perform(internal(get(MEMBERS).param(param[0], param[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errors[0].field").value(param[0]));
        }
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 401이다")
    void unauthorized() throws Exception {
        mockMvc.perform(get(MEMBERS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(MEMBERS).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("회원 세션만으로는 부를 수 없다 (관리자로 로그인해도 401)")
    void memberSessionRejected() throws Exception {
        User admin = userRepository.save(MemberFixture.member(Role.ADMIN));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(admin.getId(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + Role.ADMIN.name())))));

        mockMvc.perform(get(MEMBERS).session(session))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(MEMBERS).with(TestLogin.as(admin.getId(), Role.ADMIN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내부 키로는 일반 회원 API를 통과할 수 없다")
    void internalKeyCannotCallMemberApi() throws Exception {
        mockMvc.perform(internal(get("/api/v1/members/me")))
                .andExpect(status().isUnauthorized());
    }

    private List<User> saveMembers(int count) {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            users.add(userRepository.save(MemberFixture.user()));
        }
        return users;
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }
}
