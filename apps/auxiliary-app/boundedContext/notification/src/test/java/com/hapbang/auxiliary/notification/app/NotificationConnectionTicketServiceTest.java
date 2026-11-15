package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.global.ratelimit.RateLimitExceededException;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicketResponse;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.shared.member.domain.Role;

/**
 * 연결 티켓 발급·사용(ND-056·ND-058, test-spec NT-RT-05·06). 티켓 유효 시간(30초)과 연결 만료(최대 15분)는 다른 값이다.
 * {@code X-User-Token-Exp}는 N4 전까지 믿을 수 있는 헤더가 아니므로 연결 만료를 앞당길 수만 있고 늘릴 수 없다.
 */
class NotificationConnectionTicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final Duration TICKET_TTL = Duration.ofSeconds(30);

    private final FakeStore store = new FakeStore();
    private final NotificationConnectionTicketService service = service(store);

    @Test
    void 티켓은_30초짜리이고_연결_만료는_발급_시각_더하기_15분이다() {
        ConnectionTicketResponse response = service.issue(1L, Role.USER, null);

        assertThat(response.ticket()).matches("[A-Za-z0-9_-]{43}");
        assertThat(response.expiresInSeconds()).isEqualTo(30);
        assertThat(response.connectionExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(store.savedTtl).isEqualTo(TICKET_TTL);
        assertThat(store.saved.get(response.ticket()).connectionExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void 헤더의_exp가_15분보다_이르면_연결_만료를_그_시각으로_앞당긴다() {
        Instant exp = NOW.plus(Duration.ofMinutes(3));

        ConnectionTicketResponse response = service.issue(1L, Role.USER, String.valueOf(exp.getEpochSecond()));

        assertThat(response.connectionExpiresAt()).isEqualTo(exp);
    }

    @Test
    void 위조된_헤더로_먼_미래를_넣어도_연결_만료는_15분을_넘지_않는다() {
        Instant forged = NOW.plus(Duration.ofDays(365));

        ConnectionTicketResponse response = service.issue(1L, Role.USER, String.valueOf(forged.getEpochSecond()));

        assertThat(response.connectionExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void 헤더가_숫자가_아니거나_비었으면_15분_상한을_쓴다() {
        for (String header : new String[] {"abc", "", " ", "1.5", "99999999999999999999"}) {
            assertThat(service.issue(1L, Role.USER, header).connectionExpiresAt()).as(header)
                    .isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        }
    }

    @Test
    void 같은_회원이_창_안에서_11번째로_받으면_공통_요청_한도_초과로_창_길이만큼_뒤에_다시_받게_한다() {
        for (int i = 0; i < 10; i++) {
            service.issue(1L, Role.USER, null);
        }

        // 잠시 뒤 다시 보내면 되는 거절은 공통 RateLimitExceededException(429 + Retry-After, DEBUG 로그)이다(docs/dev/logging.md)
        assertThatThrownBy(() -> service.issue(1L, Role.USER, null))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(60));
        assertThat(service.issue(2L, Role.USER, null).ticket()).isNotBlank();
    }

    @Test
    void 저장소가_없거나_저장에_실패하면_503이다() {
        NotificationConnectionTicketService noStore = new NotificationConnectionTicketService(() -> null,
                Clock.fixed(NOW, ZoneOffset.UTC), TICKET_TTL, 10, Duration.ofSeconds(60));
        assertThatThrownBy(() -> noStore.issue(1L, Role.USER, null))
                .isInstanceOfSatisfying(NotificationException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NotificationErrorCode.CONNECTION_TICKET_UNAVAILABLE));

        store.failSave = true;
        assertThatThrownBy(() -> service.issue(1L, Role.USER, null))
                .isInstanceOfSatisfying(NotificationException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(NotificationErrorCode.CONNECTION_TICKET_UNAVAILABLE));
    }

    @Test
    void 발급_횟수를_세지_못하면_상한_확인을_건너뛰고_발급한다() {
        store.failCount = true;

        for (int i = 0; i < 20; i++) {
            assertThat(service.issue(1L, Role.USER, null).ticket()).isNotBlank();
        }
    }

    @Test
    void 티켓은_한_번만_쓸_수_있다() {
        String ticket = service.issue(1L, Role.INFLUENCER, null).ticket();

        Optional<ConnectionTicket> first = service.consume(ticket);
        Optional<ConnectionTicket> second = service.consume(ticket);

        assertThat(first).hasValueSatisfying(t -> {
            assertThat(t.userId()).isEqualTo(1L);
            assertThat(t.role()).isEqualTo(Role.INFLUENCER);
        });
        assertThat(second).isEmpty();
    }

    @Test
    void 모양이_틀린_티켓은_저장소를_부르지_않고_거절한다() {
        for (String ticket : new String[] {null, "", "short", "!".repeat(43)}) {
            assertThat(service.consume(ticket)).as(String.valueOf(ticket)).isEmpty();
        }
        assertThat(store.consumeCalls).hasValue(0);
    }

    @Test
    void 저장소_오류로_티켓을_읽지_못하면_연결을_거절한다() {
        String ticket = service.issue(1L, Role.USER, null).ticket();
        store.failConsume = true;

        assertThat(service.consume(ticket)).isEmpty();
    }

    private static NotificationConnectionTicketService service(FakeStore store) {
        return new NotificationConnectionTicketService(() -> store, Clock.fixed(NOW, ZoneOffset.UTC), TICKET_TTL, 10,
                Duration.ofSeconds(60));
    }

    private static final class FakeStore implements NotificationConnectionTicketStore {

        final Map<String, ConnectionTicket> saved = new HashMap<>();
        final Map<Long, Integer> counts = new HashMap<>();
        final AtomicInteger consumeCalls = new AtomicInteger();
        Duration savedTtl;
        boolean failSave;
        boolean failCount;
        boolean failConsume;

        @Override
        public void save(String ticket, ConnectionTicket value, Duration ttl) {
            if (failSave) {
                throw new IllegalStateException("redis down");
            }
            saved.put(ticket, value);
            savedTtl = ttl;
        }

        @Override
        public Optional<ConnectionTicket> consume(String ticket) {
            consumeCalls.incrementAndGet();
            if (failConsume) {
                throw new IllegalStateException("redis down");
            }
            return Optional.ofNullable(saved.remove(ticket));
        }

        @Override
        public long incrementIssueCount(Long userId, Duration window) {
            if (failCount) {
                throw new IllegalStateException("redis down");
            }
            return counts.merge(userId, 1, Integer::sum);
        }
    }
}
