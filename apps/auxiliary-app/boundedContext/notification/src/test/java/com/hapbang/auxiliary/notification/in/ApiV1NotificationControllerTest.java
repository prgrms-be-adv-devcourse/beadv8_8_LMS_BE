package com.hapbang.auxiliary.notification.in;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.auxiliary.notification.app.NotificationUserSyncService;
import com.hapbang.auxiliary.notification.app.NotificationWriter;
import com.hapbang.auxiliary.notification.support.TestNotificationTemplate;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 알림 REST(`/api/v1/notifications`, ND-021~024·026·052). 사용자는 Gateway가 넘기는 사용자 헤더로 정한다.
 * test-spec NT-QRY-01~04, NT-READ-01~06.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(TestNotificationTemplate.Config.class)
class ApiV1NotificationControllerTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(20_000);
    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private NotificationWriter writer;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 목록·안 읽은 수

    @Test
    void 목록은_삭제되지_않은_최신_20개를_발생_시각_내림차순으로_준다() throws Exception {
        long a = member();
        for (int i = 0; i < 25; i++) {
            writer.save(draft(a, "s-" + i, 7L, "호스트", T0.plusSeconds(i)));
        }

        mvc.perform(as(get("/api/v1/notifications"), a, Role.USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.length()").value(20))
                .andExpect(jsonPath("$.data[0].targetId").value("s-24"))
                .andExpect(jsonPath("$.data[19].targetId").value("s-5"))
                .andExpect(jsonPath("$.data[0].message").value("호스트님이 강의 s-24에 반응했습니다."))
                .andExpect(jsonPath("$.data[0].kind").value(TestNotificationTemplate.KIND))
                .andExpect(jsonPath("$.data[0].targetType").value("LECTURE"))
                .andExpect(jsonPath("$.data[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.data[0].payload").doesNotExist());
    }

    @Test
    void 정렬은_받은_순서가_아니라_발생_시각이다() throws Exception {
        long a = member();
        writer.save(draft(a, "late", 7L, "호스트", T0.plusSeconds(10)));
        writer.save(draft(a, "early", 7L, "호스트", T0));

        mvc.perform(as(get("/api/v1/notifications"), a, Role.INFLUENCER))
                .andExpect(jsonPath("$.data[0].targetId").value("late"))
                .andExpect(jsonPath("$.data[1].targetId").value("early"));
    }

    @Test
    void 다른_회원의_알림은_목록과_안_읽은_수에_나오지_않는다() throws Exception {
        long a = member();
        long b = member();
        writer.save(draft(a, "a-1", 7L, "호스트", T0));
        writer.save(draft(b, "b-1", 7L, "호스트", T0));

        mvc.perform(as(get("/api/v1/notifications"), b, Role.VERIFIED_INFLUENCER))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].targetId").value("b-1"));
        mvc.perform(as(get("/api/v1/notifications/unread-count"), b, Role.USER))
                .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void 로그인하지_않으면_401이고_관리자는_403이다() throws Exception {
        long admin = member();

        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/notifications/read-all")).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/v1/notifications"), admin, Role.ADMIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(as(get("/api/v1/notifications/unread-count"), admin, Role.ADMIN))
                .andExpect(status().isForbidden());
        mvc.perform(as(patch("/api/v1/notifications/read-all"), admin, Role.ADMIN))
                .andExpect(status().isForbidden());
    }

    @Test
    void 대상이_삭제돼도_알림과_이동_대상은_그대로_나온다() throws Exception {
        long a = member();
        writer.save(draft(a, "gone", 7L, "호스트", T0));

        // 알림 모듈에는 대상 삭제로 알림을 지우는 경로가 없다. 대상 쪽 삭제는 알림에 영향이 없다
        mvc.perform(as(get("/api/v1/notifications"), a, Role.USER))
                .andExpect(jsonPath("$.data[0].targetType").value("LECTURE"))
                .andExpect(jsonPath("$.data[0].targetId").value("gone"));
    }

    // 읽음

    @Test
    void 알림을_누르면_읽음이_되고_안_읽은_수가_준다_목록을_연_것만으로는_읽지_않는다() throws Exception {
        long a = member();
        long first = writer.save(draft(a, "s-1", 7L, "호스트", T0)).notificationId();
        writer.save(draft(a, "s-2", 7L, "호스트", T0.plusSeconds(1)));

        mvc.perform(as(get("/api/v1/notifications"), a, Role.USER)).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/notifications/unread-count"), a, Role.USER))
                .andExpect(jsonPath("$.data.count").value(2));

        mvc.perform(as(patch("/api/v1/notifications/{id}/read", first), a, Role.USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(first))
                .andExpect(jsonPath("$.data.readAt").exists());
        mvc.perform(as(get("/api/v1/notifications/unread-count"), a, Role.USER))
                .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void 이미_읽은_알림을_다시_읽어도_200이고_처음_읽은_시각이_남는다() throws Exception {
        long a = member();
        long id = writer.save(draft(a, "s-1", 7L, "호스트", T0)).notificationId();
        mvc.perform(as(patch("/api/v1/notifications/{id}/read", id), a, Role.USER)).andExpect(status().isOk());
        Object firstReadAt = readAtOf(id);

        mvc.perform(as(patch("/api/v1/notifications/{id}/read", id), a, Role.USER)).andExpect(status().isOk());

        assertThat(readAtOf(id)).isEqualTo(firstReadAt);
    }

    @Test
    void 남의_알림_없는_알림_밀려난_알림의_읽음은_모두_같은_404이고_남의_알림은_그대로다() throws Exception {
        long a = member();
        long b = member();
        long othersId = writer.save(draft(a, "a-1", 7L, "호스트", T0)).notificationId();
        for (int i = 0; i < 21; i++) {
            writer.save(draft(b, "b-" + i, 7L, "호스트", T0.plusSeconds(i)));
        }
        long pushedOutId = jdbcTemplate.queryForObject("select id from notification.notification "
                + "where receiver_user_id = ? and source_target_id = 'b-0'", Long.class, b);
        long missingId = othersId + 1_000_000;

        for (long id : new long[]{othersId, missingId, pushedOutId}) {
            mvc.perform(as(patch("/api/v1/notifications/{id}/read", id), b, Role.USER))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").value("알림을 찾을 수 없습니다."));
        }
        assertThat(readAtOf(othersId)).isNull();
        assertThat(readAtOf(pushedOutId)).isNull();
    }

    @Test
    void 전체_읽음은_내_알림만_읽음으로_바꾸고_다시_해도_결과가_같다() throws Exception {
        long a = member();
        long b = member();
        writer.save(draft(a, "a-1", 7L, "호스트", T0));
        writer.save(draft(a, "a-2", 7L, "호스트", T0.plusSeconds(1)));
        writer.save(draft(b, "b-1", 7L, "호스트", T0));

        mvc.perform(as(patch("/api/v1/notifications/read-all"), a, Role.USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(2));
        mvc.perform(as(patch("/api/v1/notifications/read-all"), a, Role.USER))
                .andExpect(jsonPath("$.data.updatedCount").value(0));

        mvc.perform(as(get("/api/v1/notifications/unread-count"), a, Role.USER))
                .andExpect(jsonPath("$.data.count").value(0));
        mvc.perform(as(get("/api/v1/notifications/unread-count"), b, Role.USER))
                .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void 안_읽은_수는_남은_20개_안에서만_센다() throws Exception {
        long a = member();
        for (int i = 0; i < 25; i++) {
            writer.save(draft(a, "s-" + i, 7L, "호스트", T0.plusSeconds(i)));
        }

        mvc.perform(as(get("/api/v1/notifications/unread-count"), a, Role.USER))
                .andExpect(jsonPath("$.data.count").value(20));
    }

    // ---------------------------------------------------------------------------------------------

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.USER, UserStatus.ACTIVE, 0L));
        return id;
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long userId, Role role) {
        return request.header(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId))
                .header(GatewayUserHeaderFilter.USER_ROLE, role.name());
    }

    private Object readAtOf(long id) {
        return jdbcTemplate.queryForObject("select read_at from notification.notification where id = ?",
                Object.class, id);
    }
}
