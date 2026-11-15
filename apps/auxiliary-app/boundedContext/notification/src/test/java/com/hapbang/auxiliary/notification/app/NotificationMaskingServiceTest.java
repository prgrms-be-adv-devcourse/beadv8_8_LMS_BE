package com.hapbang.auxiliary.notification.app;

import static com.hapbang.auxiliary.notification.support.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.auxiliary.notification.out.NotificationRepository;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.auxiliary.notification.support.TestNotificationTemplate;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 파기된 회원의 닉네임(ND-036·ND-051·ND-054, test-spec NT-USR-07). 이미 저장된 알림을 바꾸고, 파기가 처리된 뒤 저장되는 알림에도
 * 옛 닉네임이 남지 않는다.
 */
@IntegrationTest
@Import(TestNotificationTemplate.Config.class)
class NotificationMaskingServiceTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(30_000);
    private static final Instant T0 = Instant.parse("2026-11-10T00:00:00Z");

    @Autowired
    private NotificationMaskingService maskingService;

    @Autowired
    private NotificationWriter writer;

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private NotificationUserRepository users;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 파기된_회원이_언급된_알림만_닉네임과_문구를_바꾸고_다시_받아도_결과가_같다() {
        long receiver = member();
        long actor = NEXT_ID.incrementAndGet();
        long other = NEXT_ID.incrementAndGet();
        long mentioned = writer.save(draft(receiver, "s-1", actor, "원래닉네임", T0)).notificationId();
        long notMentioned = writer.save(draft(receiver, "s-2", other, "다른회원", T0)).notificationId();

        assertThat(maskingService.mask(actor)).isEqualTo(1);
        assertThat(maskingService.mask(actor)).isZero();

        assertThat(repository.findById(mentioned)).hasValueSatisfying(n -> {
            assertThat(n.getMessage()).isEqualTo("탈퇴회원#" + actor + "님이 강의 s-1에 반응했습니다.")
                    .doesNotContain("원래닉네임");
            assertThat(n.getPayload().toString()).doesNotContain("원래닉네임");
        });
        assertThat(repository.findById(notMentioned)).hasValueSatisfying(
                n -> assertThat(n.getMessage()).isEqualTo("다른회원님이 강의 s-2에 반응했습니다."));
    }

    @Test
    void 밀려나_Soft_Delete된_알림의_닉네임도_바꾼다() {
        long receiver = member();
        long actor = NEXT_ID.incrementAndGet();
        long pushedOut = writer.save(draft(receiver, "old", actor, "원래닉네임", T0)).notificationId();
        for (int i = 0; i < 20; i++) {
            writer.save(draft(receiver, "s-" + i, 9L, "다른회원", T0.plusSeconds(1 + i)));
        }

        maskingService.mask(actor);

        assertThat(repository.findById(pushedOut)).hasValueSatisfying(n -> {
            assertThat(n.getDeletedAt()).isNotNull();
            assertThat(n.getMessage()).doesNotContain("원래닉네임");
        });
    }

    @Test
    void 복제본이_없는_회원의_파기는_파기_상태_복제본을_만들고_있는_복제본은_파기_표시를_남긴다() {
        long absent = NEXT_ID.incrementAndGet();
        long present = member();

        maskingService.mask(absent);
        maskingService.mask(present);

        assertThat(users.findById(absent)).hasValueSatisfying(u -> {
            assertThat(u.isMasked()).isTrue();
            assertThat(u.getNickname()).isEqualTo("탈퇴회원#" + absent);
            assertThat(u.isWithdrawn()).isTrue();
        });
        assertThat(users.findById(present)).hasValueSatisfying(u -> {
            assertThat(u.isMasked()).isTrue();
            assertThat(u.getNickname()).isEqualTo("탈퇴회원#" + present);
        });
    }

    @Test
    void 파기가_처리된_뒤_옛_닉네임이_담긴_초안으로_저장해도_파기된_닉네임으로_저장한다() {
        long receiver = member();
        long actor = NEXT_ID.incrementAndGet();
        long other = NEXT_ID.incrementAndGet();
        maskingService.mask(actor);
        // 파기 뒤 늦게 온 옛 변경 이벤트도 복제본을 되살리지 않는다
        userSync.syncModified(new UserDto(actor, "원래닉네임", Role.INFLUENCER, UserStatus.ACTIVE, 99L));

        long id = writer.save(draftMentioning(receiver, "late", actor, "원래닉네임", other, "다른회원")).notificationId();

        assertThat(repository.findById(id)).hasValueSatisfying(n -> {
            assertThat(n.getMessage()).isEqualTo("탈퇴회원#" + actor + "님이 강의 late에 반응했습니다.");
            assertThat(n.getPayload().toString()).doesNotContain("원래닉네임").contains("다른회원");
        });
    }

    @Test
    void 복제본이_없는_회원의_파기와_그_회원을_언급한_저장이_동시에_일어나도_옛_닉네임이_남지_않는다() throws Exception {
        long receiver = member();
        for (int round = 0; round < 15; round++) {
            long actor = NEXT_ID.incrementAndGet();
            String source = "race-" + round;
            Instant at = T0.plusSeconds(round);
            runTogether(List.of(
                    () -> writer.save(draft(receiver, source, actor, "원래닉네임", at)),
                    () -> maskingService.mask(actor)));
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from notification.notification "
                + "where receiver_user_id = ? and (message like '%원래닉네임%' or payload::text like '%원래닉네임%')",
                Long.class, receiver)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from notification.notification "
                + "where receiver_user_id = ? and source_target_id like 'race-%'", Long.class, receiver))
                .isEqualTo(15L);
    }

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.USER, UserStatus.ACTIVE, 0L));
        return id;
    }

    private static void runTogether(List<Callable<?>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
