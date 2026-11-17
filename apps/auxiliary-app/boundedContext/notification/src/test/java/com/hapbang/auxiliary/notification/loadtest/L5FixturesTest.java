package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.ScheduleTimes;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserJoinedEvent;

/**
 * L5 예약 배치 fixture(N15, NT-LOAD-L5S·L5B·L5M·L5F, ND-191·ND-192·ND-194). 출연 요청 도착 이벤트로 출연 요청 만료 임박 예약을 만든다.
 * 보낼 시각은 운영 코드의 계산({@link ScheduleTimes#requestExpiring})으로 확인한다.
 */
class L5FixturesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime SEND_AT = LocalDateTime.parse("2026-11-16T18:00:00");
    private static final LocalDateTime EXPIRED_DEADLINE = LocalDateTime.parse("2026-11-16T17:40:00");

    @Test
    void 시나리오별_예약_수가_명세와_같다() {
        assertThat(rows(L5Fixtures.Scenario.S)).containsExactly(100, 0, 0);
        assertThat(rows(L5Fixtures.Scenario.B)).containsExactly(101, 0, 0);
        assertThat(rows(L5Fixtures.Scenario.M)).containsExactly(1_000, 0, 0);
        assertThat(rows(L5Fixtures.Scenario.F)).containsExactly(990, 10, L5Fixtures.EXPIRED_ROWS);
    }

    @Test
    void 정상_실패_예약의_보낼_시각은_모두_정한_시각이다() {
        L5Fixtures.L5Fixture fixture = L5Fixtures.generate(L5Fixtures.Scenario.F, SEND_AT, EXPIRED_DEADLINE);
        for (B0Record record : fixture.normal()) {
            assertThat(sendAt(record)).isEqualTo(SEND_AT.atZone(KST).toInstant());
        }
        for (B0Record record : fixture.failing()) {
            assertThat(sendAt(record)).isEqualTo(SEND_AT.atZone(KST).toInstant());
        }
        for (B0Record record : fixture.expired()) {
            AppearanceRequestSentEvent event = (AppearanceRequestSentEvent) record.event();
            assertThat(ScheduleTimes.requestExpiring(event.request().acceptDeadline()).businessDeadline())
                    .isEqualTo(EXPIRED_DEADLINE.atZone(KST).toInstant());
        }
    }

    @Test
    void 실패_예약의_받는_사람은_실패_전용_회원이고_다른_예약의_받는_사람과_겹치지_않는다() {
        L5Fixtures.L5Fixture fixture = L5Fixtures.generate(L5Fixtures.Scenario.F, SEND_AT, EXPIRED_DEADLINE);
        Set<Long> failingGuests = new HashSet<>(guests(fixture.failing()));
        assertThat(failingGuests).hasSize(10).containsExactlyInAnyOrderElementsOf(fixture.failingReceivers());
        assertThat(guests(fixture.normal())).doesNotContainAnyElementsOf(failingGuests);
        assertThat(guests(fixture.expired())).doesNotContainAnyElementsOf(failingGuests);
        assertThat(hosts(fixture)).doesNotContainAnyElementsOf(failingGuests);
    }

    @Test
    void 받는_사람과_호스트는_모두_활성_회원으로_먼저_복제된다() {
        L5Fixtures.L5Fixture fixture = L5Fixtures.generate(L5Fixtures.Scenario.F, SEND_AT, EXPIRED_DEADLINE);
        Set<Long> activeMembers = new HashSet<>();
        for (B0Record member : fixture.members()) {
            UserJoinedEvent joined = (UserJoinedEvent) member.event();
            assertThat(joined.user().status()).isEqualTo(UserStatus.ACTIVE);
            activeMembers.add(joined.user().id());
        }
        assertThat(activeMembers).containsAll(guests(fixture.allRequests())).containsAll(hosts(fixture));
    }

    @Test
    void 요청_ID와_key는_예약마다_다르고_같은_입력이면_같은_출력이다() {
        L5Fixtures.L5Fixture fixture = L5Fixtures.generate(L5Fixtures.Scenario.M, SEND_AT, EXPIRED_DEADLINE);
        Set<String> keys = new HashSet<>();
        fixture.allRequests().forEach(r -> keys.add(r.key()));
        assertThat(keys).hasSize(fixture.allRequests().size());
        L5Fixtures.L5Fixture again = L5Fixtures.generate(L5Fixtures.Scenario.M, SEND_AT, EXPIRED_DEADLINE);
        assertThat(again.allRequests().stream().map(B0Record::line).toList())
                .isEqualTo(fixture.allRequests().stream().map(B0Record::line).toList());
    }

    private static List<Integer> rows(L5Fixtures.Scenario scenario) {
        L5Fixtures.L5Fixture fixture = L5Fixtures.generate(scenario, SEND_AT, EXPIRED_DEADLINE);
        return List.of(fixture.normal().size(), fixture.failing().size(), fixture.expired().size());
    }

    private static java.time.Instant sendAt(B0Record record) {
        AppearanceRequestSentEvent event = (AppearanceRequestSentEvent) record.event();
        return ScheduleTimes.requestExpiring(event.request().acceptDeadline()).sendAt();
    }

    private static List<Long> guests(List<B0Record> records) {
        return records.stream().map(r -> ((AppearanceRequestSentEvent) r.event()).request().guestId()).toList();
    }

    private static Set<Long> hosts(L5Fixtures.L5Fixture fixture) {
        Set<Long> hosts = new HashSet<>();
        fixture.allRequests().forEach(r -> hosts.add(((AppearanceRequestSentEvent) r.event()).request().hostId()));
        return hosts;
    }
}
