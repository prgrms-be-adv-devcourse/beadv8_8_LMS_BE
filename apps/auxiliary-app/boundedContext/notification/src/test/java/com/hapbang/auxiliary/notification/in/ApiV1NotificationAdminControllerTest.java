package com.hapbang.auxiliary.notification.in;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.notification.app.NotificationJob;
import com.hapbang.auxiliary.notification.support.FakeMemberSource;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 회원 복제본 Backfill 관리자 재실행 API(ND-079). test-spec NT-USR-10 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(FakeMemberSource.Config.class)
class ApiV1NotificationAdminControllerTest {

    private static final String PATH = "/api/v1/notifications/admin/user-replica/backfill";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeMemberSource source;

    @Autowired
    private JobLock jobLock;

    @Test
    void 관리자는_다시_실행하고_결과_수를_받는다() throws Exception {
        source.reset(List.of(new UserDto(960_001L, "회원", Role.USER, UserStatus.ACTIVE, 1L),
                new UserDto(960_002L, " ", Role.USER, UserStatus.ACTIVE, 1L)));

        mvc.perform(as(post(PATH), 1L, Role.ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scanned").value(2))
                .andExpect(jsonPath("$.data.applied").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.skippedUserIds[0]").value(960_002L));
    }

    @Test
    void 관리자가_아니면_403_로그인하지_않으면_401이다() throws Exception {
        mvc.perform(as(post(PATH), 2L, Role.USER)).andExpect(status().isForbidden());
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void 끝까지_읽지_못하면_결과_없이_503_BACKFILL_FAILED다() throws Exception {
        source.reset(List.of(new UserDto(960_003L, "회원", Role.USER, UserStatus.ACTIVE, 1L)));
        source.failNext(1);

        mvc.perform(as(post(PATH), 1L, Role.ADMIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BACKFILL_FAILED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 다른_실행이_돌고_있으면_409다() throws Exception {
        source.reset(List.of());
        try (JobLock.Lease ignored = jobLock.tryAcquire(NotificationJob.USER_BACKFILL).orElseThrow()) {
            mvc.perform(as(post(PATH), 1L, Role.ADMIN))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("BACKFILL_RUNNING"));
        }
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long userId, Role role) {
        return request.header(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId))
                .header(GatewayUserHeaderFilter.USER_ROLE, role.name());
    }
}
