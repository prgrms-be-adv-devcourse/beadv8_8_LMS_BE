package com.hapbang.member.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.IntegrationTest;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

/** 회원 변경분 내부 API (#769): updated_at이 커서보다 뒤인 모든 회원을 (updatedAt, id) 순 키셋으로 준다 */
@MemberIntegrationTest
@AutoConfigureMockMvc
class MemberChangesInternalApiTest {

    private static final String CHANGES = "/api/v1/internal/members/changes";
    /** 응답에서 빠진(null) 필드를 읽으면 예외 대신 null */
    private static final Configuration LENIENT = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("커서 없이 부르면 처음부터 updated_at 순으로 모든 역할·상태를 UserDto로 준다")
    void firstPage() throws Exception {
        User admin = userRepository.save(MemberFixture.member(Role.ADMIN));
        User suspended = userRepository.save(MemberFixture.member(Role.INFLUENCER, UserStatus.SUSPENDED));
        User withdrawn = userRepository.save(MemberFixture.member(Role.USER, UserStatus.WITHDRAWN));
        // 가장 먼저 가입했어도 나중에 바뀌면 뒤에 나온다
        setUpdatedAt(admin, LocalDateTime.of(2026, 11, 9, 10, 0, 3));
        setUpdatedAt(suspended, LocalDateTime.of(2026, 11, 9, 10, 0, 1));
        setUpdatedAt(withdrawn, LocalDateTime.of(2026, 11, 9, 10, 0, 2));

        mockMvc.perform(internal(get(CHANGES).param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(suspended.getId()))
                .andExpect(jsonPath("$.data.items[0].status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.items[0].contentCategories").isArray())
                .andExpect(jsonPath("$.data.items[1].id").value(withdrawn.getId()))
                .andExpect(jsonPath("$.data.items[1].status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data.nextAfterUpdatedAt").value("2026-11-09T10:00:02"))
                .andExpect(jsonPath("$.data.nextAfterId").value(withdrawn.getId()))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mockMvc.perform(internal(get(CHANGES).param("afterUpdatedAt", "2026-11-09T10:00:02")
                        .param("afterId", withdrawn.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(admin.getId()))
                .andExpect(jsonPath("$.data.items[0].role").value("ADMIN"))
                // 마지막 페이지에도 마지막 회원의 커서를 준다 (받는 쪽이 저장해 다음 주기에 이어 읽는다)
                .andExpect(jsonPath("$.data.nextAfterUpdatedAt").value("2026-11-09T10:00:03"))
                .andExpect(jsonPath("$.data.nextAfterId").value(admin.getId()))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        // 그 커서 뒤로 바뀐 회원이 없으면 빈 목록이고 커서가 없다
        mockMvc.perform(internal(get(CHANGES).param("afterUpdatedAt", "2026-11-09T10:00:03")
                        .param("afterId", admin.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.nextAfterUpdatedAt").doesNotExist())
                .andExpect(jsonPath("$.data.nextAfterId").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("마지막 페이지의 커서를 저장해 두면 다음 주기에는 그 뒤에 바뀐 회원만 받는다")
    void nextCycleFromLastPageCursor() throws Exception {
        userRepository.save(MemberFixture.member(Role.INFLUENCER));
        userRepository.save(MemberFixture.member(Role.INFLUENCER));
        DocumentContext first = read(get(CHANGES));
        String cursorTime = first.read("$.data.nextAfterUpdatedAt");
        Number cursorId = first.read("$.data.nextAfterId");
        assertThat((Boolean) first.read("$.data.hasNext")).isFalse();

        User joined = userRepository.save(MemberFixture.member(Role.USER));

        mockMvc.perform(internal(get(CHANGES).param("afterUpdatedAt", cursorTime)
                        .param("afterId", cursorId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(joined.getId()));
    }

    @Test
    @DisplayName("같은 updated_at의 회원이 페이지 경계에 걸려도 id로 이어 읽어 중복·누락이 없다")
    void sameUpdatedAtAcrossPages() throws Exception {
        LocalDateTime same = LocalDateTime.of(2026, 11, 9, 10, 0, 0, 123_456_000);
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User user = userRepository.save(MemberFixture.member(Role.INFLUENCER));
            setUpdatedAt(user, same);
            expected.add(user.getId());
        }
        User later = userRepository.save(MemberFixture.member(Role.USER));
        setUpdatedAt(later, same.plusNanos(1_000));
        expected.add(later.getId());

        assertThat(walkAll(null, null, 2)).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("커서 뒤에 강등·탈퇴한 회원도 나와, 받는 쪽이 숨길 수 있다")
    void demotedAndWithdrawnAfterCursor() throws Exception {
        User demoted = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        User withdrawn = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        userRepository.save(MemberFixture.member(Role.USER));
        // 지금까지 읽은 마지막 회원(세 번째)을 커서로 둔다
        DocumentContext first = read(get(CHANGES));
        Number cursorId = first.read("$.data.items[2].id");
        String cursorTime = updatedAtOf(cursorId.longValue());

        demoted.changeRole(Role.USER);
        userRepository.saveAndFlush(demoted);
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(withdrawn.getId()).orElseThrow();
            user.withdraw(LocalDateTime.now());
        });

        mockMvc.perform(internal(get(CHANGES).param("afterUpdatedAt", cursorTime)
                        .param("afterId", cursorId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(demoted.getId()))
                .andExpect(jsonPath("$.data.items[0].role").value("USER"))
                .andExpect(jsonPath("$.data.items[0].version").value(1))
                .andExpect(jsonPath("$.data.items[1].id").value(withdrawn.getId()))
                .andExpect(jsonPath("$.data.items[1].status").value("WITHDRAWN"));
    }

    @Test
    @DisplayName("콘텐츠 분야만 바꿔도 updated_at이 올라 변경분에 나온다")
    void contentCategoriesOnlyChange() throws Exception {
        User influencer = userRepository.save(MemberFixture.member(Role.INFLUENCER));
        LocalDateTime old = LocalDateTime.of(2026, 1, 1, 0, 0);
        setUpdatedAt(influencer, old);

        transactionTemplate.executeWithoutResult(status ->
                userRepository.findById(influencer.getId()).orElseThrow()
                        .changeContentCategories(List.of("GAME", "MAPLESTORY")));

        mockMvc.perform(internal(get(CHANGES).param("afterUpdatedAt", "2026-01-01T00:00:00")
                        .param("afterId", influencer.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(influencer.getId()))
                .andExpect(jsonPath("$.data.items[0].version").value(1))
                .andExpect(jsonPath("$.data.items[0].contentCategories[0]").value("GAME"))
                .andExpect(jsonPath("$.data.items[0].contentCategories[1]").value("MAPLESTORY"));
    }

    @Test
    @DisplayName("응답의 next 커서를 그대로 넘기면 마이크로초까지 이어져 끝까지 읽힌다")
    void walkWithResponseCursor() throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(userRepository.save(MemberFixture.member(Role.INFLUENCER)).getId());
        }

        assertThat(walkAll(null, null, 2)).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("커서 둘 중 하나만 넘기거나, 시각 형식이 틀리거나 오프셋이 붙거나, size·afterId가 범위 밖이면 400 INVALID_REQUEST")
    void invalidRequest() throws Exception {
        mockMvc.perform(internal(get(CHANGES).param("afterUpdatedAt", "2026-11-09T10:00:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(internal(get(CHANGES).param("afterId", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        // 형식이 틀리거나, 오프셋이 붙은 시각(조용히 오프셋을 버리면 커서가 시차만큼 어긋난다)
        for (String time : new String[] {"2026-11-09 10:00", "2026-11-09T01:15:30Z", "2026-11-09T10:15:30+09:00"}) {
            mockMvc.perform(internal(get(CHANGES).param("afterUpdatedAt", time).param("afterId", "1")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errors[0].field").value("afterUpdatedAt"));
        }
        // 형식은 맞지만 있을 수 없는 날짜
        mockMvc.perform(internal(get(CHANGES).param("afterUpdatedAt", "2026-13-09T10:00:00").param("afterId", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String[] param : new String[][] {{"size", "0"}, {"size", "501"}}) {
            mockMvc.perform(internal(get(CHANGES).param(param[0], param[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errors[0].field").value(param[0]));
        }
        mockMvc.perform(internal(get(CHANGES).param("afterUpdatedAt", "2026-11-09T10:00:00").param("afterId", "-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("afterId"));
    }

    @Test
    @DisplayName("내부 키가 없거나 틀리면 401이다")
    void unauthorized() throws Exception {
        mockMvc.perform(get(CHANGES))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(CHANGES).header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY + "x"))
                .andExpect(status().isUnauthorized());
    }

    /** 응답의 next 커서를 그대로 넘기며 hasNext가 false가 될 때까지 읽은 회원 ID */
    private List<Long> walkAll(String afterUpdatedAt, Long afterId, int size) throws Exception {
        List<Long> collected = new ArrayList<>();
        boolean hasNext;
        do {
            MockHttpServletRequestBuilder request = get(CHANGES).param("size", String.valueOf(size));
            if (afterUpdatedAt != null) {
                request.param("afterUpdatedAt", afterUpdatedAt).param("afterId", afterId.toString());
            }
            DocumentContext body = read(request);
            List<Number> ids = body.read("$.data.items[*].id");
            ids.forEach(id -> collected.add(id.longValue()));
            afterUpdatedAt = body.read("$.data.nextAfterUpdatedAt");
            Number next = body.read("$.data.nextAfterId");
            afterId = next == null ? null : next.longValue();
            hasNext = body.read("$.data.hasNext");
        } while (hasNext);
        return collected;
    }

    private DocumentContext read(MockHttpServletRequestBuilder request) throws Exception {
        return JsonPath.using(LENIENT).parse(
                mockMvc.perform(internal(request)).andReturn().getResponse().getContentAsString());
    }

    /** 회원의 updated_at (DB 값)을 커서 형식으로 */
    private String updatedAtOf(Long userId) {
        LocalDateTime updatedAt = jdbcTemplate.queryForObject("select updated_at from members.users where id = ?",
                Timestamp.class, userId).toLocalDateTime();
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(updatedAt);
    }

    private void setUpdatedAt(User user, LocalDateTime updatedAt) {
        jdbcTemplate.update("update members.users set updated_at = ? where id = ?",
                Timestamp.valueOf(updatedAt), user.getId());
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }
}
