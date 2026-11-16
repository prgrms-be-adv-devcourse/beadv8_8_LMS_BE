package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.event.UserJoinedEvent;

/**
 * L3 저장→실시간 전달 fixture(N15, NT-LOAD-L3, ND-193). 연결한 회원에게 가는 단건 알림(충전 완료)만 만든다. 하루 묶음처럼 같은 행을
 * 갱신하는 알림은 넣지 않아 알림 행 하나가 프레임 하나로 맞는다.
 */
class L3FixturesTest {

    private static final L3Fixtures.L3Fixture FIXTURE = L3Fixtures.generate(100, 6_000);

    @Test
    void 연결할_회원은_모두_활성이고_정한_수만큼이다() {
        assertThat(FIXTURE.members()).hasSize(100).allSatisfy(r -> assertThat(
                ((UserJoinedEvent) r.event()).user().status()).isEqualTo(UserStatus.ACTIVE));
        assertThat(FIXTURE.receivers()).hasSize(100);
    }

    @Test
    void 알림_이벤트는_정한_수의_충전_완료이고_받는_사람은_연결할_회원이며_사건이_모두_다르다() {
        assertThat(FIXTURE.alerts()).hasSize(6_000);
        Set<Long> charges = new HashSet<>();
        Set<Long> receivers = new HashSet<>(FIXTURE.receivers());
        for (B0Record record : FIXTURE.alerts()) {
            ChargeCompletedEvent event = (ChargeCompletedEvent) record.event();
            assertThat(receivers).contains(event.userId());
            charges.add(event.chargeId());
        }
        assertThat(charges).hasSize(6_000);
    }

    @Test
    void 받는_사람마다_고르게_나눈다() {
        long first = FIXTURE.receivers().getFirst();
        long count = FIXTURE.alerts().stream()
                .filter(r -> ((ChargeCompletedEvent) r.event()).userId() == first).count();
        assertThat(count).isEqualTo(60);
    }
}
