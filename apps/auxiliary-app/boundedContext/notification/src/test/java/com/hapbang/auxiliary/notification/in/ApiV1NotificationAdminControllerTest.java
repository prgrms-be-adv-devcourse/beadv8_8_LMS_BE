package com.hapbang.auxiliary.notification.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.notification.app.BackfillRun;
import com.hapbang.auxiliary.notification.app.BackfillRunStatus;
import com.hapbang.auxiliary.notification.app.BackfillRunStore;
import com.hapbang.auxiliary.notification.app.NotificationJob;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.auxiliary.notification.support.FakeMemberSource;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.RedisTestcontainersConfig;

import tools.jackson.databind.json.JsonMapper;

/**
 * 회원 복제본 Backfill 관리자 재실행 API(ND-079·ND-089·ND-092). 시작 요청은 202와 실행 ID, 결과는 조회 API로 본다. 실행 상태는
 * Redis에 두고 실제 실행과 맞는지 본다: 완료 결과가 실제 반영과 같은지, 페이지마다 갱신하는지, 시작 기록 실패·409 때 실행·기록이 없는지,
 * 끝 상태 기록 실패 때도 잠금이 풀리는지, 갱신이 멈춘 RUNNING은 UNKNOWN인지. 각 시험은 시작한 실행이 끝 상태가 될 때까지 기다려 다음
 * 시험에 잠금·공유 상태를 남기지 않는다. 페이지 크기 1로 페이지마다 갱신을 센다. test-spec NT-USR-10
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({FakeMemberSource.Config.class, RedisTestcontainersConfig.class})
@TestPropertySource(properties = {
        "hapbang.notification.realtime.redis-prefix=test-backfill-${random.uuid}",
        "hapbang.notification.user-backfill.page-size=1"
})
class ApiV1NotificationAdminControllerTest {

    private static final String PATH = "/api/v1/notifications/admin/user-replica/backfill";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeMemberSource source;

    @Autowired
    private JobLock jobLock;

    @Autowired
    private NotificationUserRepository users;

    @MockitoSpyBean
    private BackfillRunStore runs;

    @Test
    void 관리자가_시작하면_202와_실행_ID를_받고_조회하면_실제_반영과_같은_완료_결과를_본다() throws Exception {
        source.reset(List.of(new UserDto(960_001L, "회원", Role.USER, UserStatus.ACTIVE, 1L),
                new UserDto(960_002L, " ", Role.USER, UserStatus.ACTIVE, 1L)));

        String runId = start();

        awaitStatus(runId, BackfillRunStatus.COMPLETED);
        mvc.perform(as(get(PATH + "/" + runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.scanned").value(2))
                .andExpect(jsonPath("$.data.result.applied").value(1))
                .andExpect(jsonPath("$.data.result.skipped").value(1))
                .andExpect(jsonPath("$.data.result.skippedUserIds[0]").value(960_002L))
                .andExpect(jsonPath("$.data.finishedAt").exists())
                .andExpect(jsonPath("$.data.failureCode").doesNotExist());
        assertThat(users.findById(960_001L)).isPresent();
        assertThat(users.findById(960_002L)).isEmpty();
        // 시작 1 + 페이지마다 갱신 2(페이지 크기 1, 회원 2) + 끝 1
        verify(runs, atLeast(4)).save(any());
    }

    @Test
    void 끝까지_읽지_못하면_FAILED와_실패_코드이고_결과는_없으며_잠금이_풀려_다시_시작할_수_있다() throws Exception {
        source.reset(List.of(new UserDto(960_003L, "회원", Role.USER, UserStatus.ACTIVE, 1L)));
        source.failNext(1);

        String runId = start();

        awaitStatus(runId, BackfillRunStatus.FAILED);
        mvc.perform(as(get(PATH + "/" + runId)))
                .andExpect(jsonPath("$.data.failureCode").value("BACKFILL_FAILED"))
                .andExpect(jsonPath("$.data.result").doesNotExist());
        String second = start();
        assertThat(second).isNotEqualTo(runId);
        awaitStatus(second, BackfillRunStatus.COMPLETED);
    }

    @Test
    void 다른_실행이_돌고_있으면_409이고_실행도_기록도_하지_않는다() throws Exception {
        source.reset(List.of());
        try (JobLock.Lease ignored = jobLock.tryAcquire(NotificationJob.USER_BACKFILL).orElseThrow()) {
            mvc.perform(as(post(PATH)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("BACKFILL_RUNNING"));
        }
        assertThat(source.calls()).isZero();
        verify(runs, never()).save(any());
    }

    @Test
    void 시작_기록을_Redis에_쓰지_못하면_503이고_실행하지_않으며_잠금을_푼다() throws Exception {
        source.reset(List.of(new UserDto(960_004L, "회원", Role.USER, UserStatus.ACTIVE, 1L)));
        doThrow(new IllegalStateException("Redis 쓰기 실패(시험)")).when(runs).save(any());

        mvc.perform(as(post(PATH)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BACKFILL_STATUS_UNAVAILABLE"));
        assertThat(source.calls()).isZero();
        assertThat(users.findById(960_004L)).isEmpty();

        doCallRealMethod().when(runs).save(any());
        awaitStatus(start(), BackfillRunStatus.COMPLETED);
    }

    @Test
    void 끝_상태를_쓰지_못해도_잠금은_풀리고_기록은_완료로_보이지_않는다() throws Exception {
        source.reset(List.of(new UserDto(960_005L, "회원", Role.USER, UserStatus.ACTIVE, 1L)));
        doThrow(new IllegalStateException("Redis 쓰기 실패(시험)")).when(runs)
                .save(argThat(run -> run != null && run.status() == BackfillRunStatus.COMPLETED));

        String runId = start();

        // 실행은 끝나 잠금이 풀린다(다음 시작이 409가 아니다). 기록은 RUNNING에 머물러 갱신이 멈추면 5분 뒤 UNKNOWN이 된다
        await().atMost(TIMEOUT).until(() -> users.findById(960_005L).isPresent());
        await().atMost(TIMEOUT).until(() -> jobLock.tryAcquire(NotificationJob.USER_BACKFILL)
                .map(lease -> {
                    lease.close();
                    return true;
                }).orElse(false));
        mvc.perform(as(get(PATH + "/" + runId)))
                .andExpect(jsonPath("$.data.status").value(BackfillRunStatus.RUNNING.name()));
    }

    @Test
    void 갱신이_5분_넘게_없는_RUNNING은_UNKNOWN으로_보이고_없는_실행은_404다() throws Exception {
        Instant old = Instant.now().minus(Duration.ofMinutes(6));
        runs.save(BackfillRun.running("stale-run", old));

        mvc.perform(as(get(PATH + "/stale-run")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(BackfillRunStatus.UNKNOWN.name()));
        mvc.perform(as(get(PATH + "/no-such-run")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BACKFILL_RUN_NOT_FOUND"));
    }

    @Test
    void 관리자가_아니면_403_로그인하지_않으면_401이다() throws Exception {
        mvc.perform(as(post(PATH), 2L, Role.USER)).andExpect(status().isForbidden());
        mvc.perform(as(get(PATH + "/any"), 2L, Role.USER)).andExpect(status().isForbidden());
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/any")).andExpect(status().isUnauthorized());
    }

    private String start() throws Exception {
        String body = mvc.perform(as(post(PATH)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("data").path("runId").asString();
    }

    private void awaitStatus(String runId, BackfillRunStatus expected) {
        await().atMost(TIMEOUT).untilAsserted(() -> mvc.perform(as(get(PATH + "/" + runId)))
                .andExpect(jsonPath("$.data.status").value(expected.name())));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return as(request, 1L, Role.ADMIN);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long userId, Role role) {
        return request.header(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId))
                .header(GatewayUserHeaderFilter.USER_ROLE, role.name());
    }
}
