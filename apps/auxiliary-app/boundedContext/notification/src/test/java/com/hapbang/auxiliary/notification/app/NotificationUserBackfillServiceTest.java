package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hapbang.auxiliary.global.scheduling.JobLock;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.auxiliary.notification.support.FakeMemberSource;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 회원 복제본 Backfill(ND-077·ND-078). 페이지 크기를 2로 줄여 여러 페이지를 본다.
 * test-spec NT-USR-06·08·09
 */
@IntegrationTest
@Import(FakeMemberSource.Config.class)
@TestPropertySource(properties = "hapbang.notification.user-backfill.page-size=2")
class NotificationUserBackfillServiceTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(900_000);

    @Autowired
    private NotificationUserBackfillService backfill;

    @Autowired
    private FakeMemberSource source;

    @Autowired
    private NotificationUserRepository users;

    @Autowired
    private NotificationUserSyncService sync;

    @Autowired
    private JobLock jobLock;

    @Test
    void 여러_페이지를_마지막까지_읽어_없는_복제본을_모두_만든다() {
        List<UserDto> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(user(nextId(), "회원" + i, Role.USER, 1L));
        }
        source.reset(all);

        BackfillResult result = backfill.run();

        assertThat(result.scanned()).isEqualTo(5);
        assertThat(result.applied()).isEqualTo(5);
        assertThat(source.calls()).isEqualTo(3);
        all.forEach(u -> assertThat(users.findById(u.id())).hasValueSatisfying(r -> {
            assertThat(r.getNickname()).isEqualTo(u.nickname());
            assertThat(r.getMemberVersion()).isEqualTo(1L);
        }));
    }

    @Test
    void 더_큰_버전만_반영하고_같거나_낮은_버전과_파기된_복제본은_그대로_둔다() {
        long newer = nextId();
        long same = nextId();
        long older = nextId();
        long masked = nextId();
        sync.syncJoined(user(newer, "최신", Role.VERIFIED_INFLUENCER, 5L));
        sync.syncJoined(user(same, "그대로", Role.INFLUENCER, 3L));
        sync.syncJoined(user(older, "옛것", Role.USER, 1L));
        users.save(NotificationUser.createMasked(masked, Instant.parse("2026-11-10T00:00:00Z")));
        source.reset(List.of(user(newer, "낡은값", Role.USER, 4L), user(same, "같은버전", Role.USER, 3L),
                user(older, "새값", Role.INFLUENCER, 2L), user(masked, "옛닉네임", Role.USER, 9L)));

        BackfillResult result = backfill.run();

        assertThat(result.scanned()).isEqualTo(4);
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(3);
        assertThat(users.findById(newer).orElseThrow().getNickname()).isEqualTo("최신");
        assertThat(users.findById(same).orElseThrow().getNickname()).isEqualTo("그대로");
        assertThat(users.findById(older).orElseThrow()).satisfies(r -> {
            assertThat(r.getNickname()).isEqualTo("새값");
            assertThat(r.getRole()).isEqualTo(Role.INFLUENCER);
            assertThat(r.getMemberVersion()).isEqualTo(2L);
        });
        assertThat(users.findById(masked).orElseThrow()).satisfies(r -> {
            assertThat(r.getNickname()).isEqualTo("탈퇴회원#" + masked);
            assertThat(r.isMasked()).isTrue();
        });
    }

    @Test
    void Backfill이_반영한_복제본도_뒤에_온_Kafka_변경이_더_큰_버전이면_덮는다() {
        long id = nextId();
        source.reset(List.of(user(id, "백필", Role.USER, 2L)));

        backfill.run();
        sync.syncModified(user(id, "카프카", Role.INFLUENCER, 3L));

        assertThat(users.findById(id).orElseThrow()).satisfies(r -> {
            assertThat(r.getNickname()).isEqualTo("카프카");
            assertThat(r.getMemberVersion()).isEqualTo(3L);
        });
    }

    @Test
    void 복제할_수_없는_회원은_건너뜀으로_ID를_남기고_나머지는_반영하며_완료다() {
        long ok = nextId();
        long invalid = nextId();
        source.reset(List.of(user(ok, "정상", Role.USER, 1L),
                new UserDto(invalid, " ", Role.USER, UserStatus.ACTIVE, 1L)));

        BackfillResult result = backfill.run();

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.skippedUserIds()).containsExactly(invalid);
        assertThat(users.findById(ok)).isPresent();
        assertThat(users.findById(invalid)).isEmpty();
    }

    @Test
    void 회원_API_일시_장애면_실패로_끝나고_다시_실행하면_마저_채운다() {
        long a = nextId();
        long b = nextId();
        long c = nextId();
        source.reset(List.of(user(a, "a", Role.USER, 1L), user(b, "b", Role.USER, 1L), user(c, "c", Role.USER, 1L)));
        source.failNext(1);

        assertThatThrownBy(() -> backfill.run()).isInstanceOf(IllegalStateException.class);
        BackfillResult retried = backfill.run();

        assertThat(retried.scanned()).isEqualTo(3);
        assertThat(users.findById(c)).isPresent();
    }

    @Test
    void 다른_실행이_잠금을_쥐고_있으면_실행하지_않고_실행_중이라고_알린다() {
        source.reset(List.of(user(nextId(), "x", Role.USER, 1L)));

        try (JobLock.Lease ignored = jobLock.tryAcquire(NotificationJob.USER_BACKFILL).orElseThrow()) {
            assertThatThrownBy(() -> backfill.run()).isInstanceOfSatisfying(NotificationException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(NotificationErrorCode.BACKFILL_RUNNING));
        }
        assertThat(source.calls()).isZero();
    }

    private static UserDto user(long id, String nickname, Role role, Long version) {
        return new UserDto(id, nickname, role, UserStatus.ACTIVE, version);
    }

    private static long nextId() {
        return NEXT_ID.incrementAndGet();
    }
}
