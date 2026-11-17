package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * L4 밀린 이벤트 회복 fixture(N15, NT-LOAD-L4, ND-189·ND-190). B0 생성기 규칙을 그대로 쓰되 크기만 다르고, 기대 결과는 L4 manifest로
 * 따로 계산한다. 정상 백로그 10,000건에는 재시도·DLT 대상이 없고, 실패 단계 레코드는 맨 뒤에 따로 둔다.
 */
class L4FixtureGeneratorTest {

    private static final B0Fixture FIXTURE = L4Fixtures.generate();

    private static List<B0Record> normal() {
        return FIXTURE.alerts().stream().filter(r -> !r.category().failurePhase()).toList();
    }

    private static List<B0Record> failure() {
        return FIXTURE.alerts().stream().filter(r -> r.category().failurePhase()).toList();
    }

    @Test
    void 정상_백로그는_정확히_10000건이고_재시도_DLT_대상이_없다() {
        assertThat(normal()).hasSize(10_000);
        assertThat(FIXTURE.manifest().phaseRecordsByTopic().get("normal").values().stream()
                .mapToInt(Integer::intValue).sum()).isEqualTo(10_000);
    }

    @Test
    void 결과_문서에_적은_분류별_건수와_같다() {
        // docs/load-test/notification-load.md L4 fixture: 지갑 4,147·출연 2,853·강의 2,001·고객센터 501·채팅 498, 실패 지갑 150
        assertThat(FIXTURE.manifest().phaseRecordsByTopic().get("normal")).containsExactlyInAnyOrderEntriesOf(Map.of(
                "billing.events.v1", 4_147, "appearance.events.v1", 2_853, "lecture.events.v1", 2_001,
                "customersupport.events.v1", 501, "chat.events.v1", 498));
        assertThat(FIXTURE.manifest().phaseRecordsByTopic().get("failure")).containsExactlyEntriesOf(
                Map.of("billing.events.v1", 150));
        assertThat(FIXTURE.manifest().deadLetters()).containsExactlyInAnyOrderEntriesOf(
                Map.of("NotificationException", 100, "DeserializationException", 50));
    }

    @Test
    void 재전송이_없는_크기에서도_만든다() {
        B0FixtureGenerator.Sizes s = L4Fixtures.SIZES;
        B0FixtureGenerator.Sizes noResend = new B0FixtureGenerator.Sizes(s.charges(), s.appearancePayouts(),
                s.lecturePayouts(), s.requestsSent(), s.requestsCanceled(), s.requestsExpired(), s.applications(),
                s.reviews(), s.ordersPaid(), s.itemsConfirmed(), s.lecturesApproved(), s.inquiries(), s.invitations(), 0,
                s.legacy(), s.unknownReceivers(), s.broken());
        B0Fixture fixture = B0FixtureGenerator.generate(L4Fixtures.SEED, noResend);
        assertThat(fixture.alerts()).noneMatch(r -> r.category() == B0Category.RESEND);
    }

    @Test
    void 실패_단계_레코드는_모두_정상_백로그_뒤에_있고_DLT_기대_건수와_같다() {
        List<B0Record> alerts = FIXTURE.alerts();
        int firstFailure = alerts.indexOf(failure().getFirst());
        assertThat(alerts.subList(firstFailure, alerts.size())).allSatisfy(
                r -> assertThat(r.category().failurePhase()).isTrue());
        assertThat(failure()).hasSize(150);
        int deadLetters = FIXTURE.manifest().deadLetters().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(deadLetters).isEqualTo(failure().size());
    }

    @Test
    void 정상_백로그에_건너뛰기_유형이_들어간다() {
        Map<B0Category, Long> byCategory = normal().stream()
                .collect(Collectors.groupingBy(B0Record::category, Collectors.counting()));
        assertThat(byCategory).containsEntry(B0Category.LEGACY, 100L).containsEntry(B0Category.RESEND, 500L);
        int skippedReceiver = FIXTURE.manifest().outcomes().values().stream()
                .mapToInt(m -> m.getOrDefault("skipped_receiver", 0)).sum();
        int duplicate = FIXTURE.manifest().outcomes().values().stream()
                .mapToInt(m -> m.getOrDefault("duplicate", 0)).sum();
        assertThat(skippedReceiver).isPositive();
        // 재전송 500건. 받는 사람이 여럿인 단체방 초대 재전송은 받는 사람마다 같은 사건으로 센다
        assertThat(duplicate).isGreaterThanOrEqualTo(500);
    }

    @Test
    void 같은_seed면_같고_B0와는_다르다() {
        B0Fixture again = L4Fixtures.generate();
        B0Fixture b0 = B0FixtureGenerator.generate(B0FixtureGenerator.DEFAULT_SEED);

        assertThat(again.contentHash()).isEqualTo(FIXTURE.contentHash());
        assertThat(again.manifest()).isEqualTo(FIXTURE.manifest());
        assertThat(FIXTURE.contentHash()).isNotEqualTo(b0.contentHash());
    }

    @Test
    void 예약은_2027년_이후라_L4_중에_예약_알림이_나가지_않고_사건은_알림_시작_뒤다() {
        Instant first2027 = Instant.parse("2027-01-01T00:00:00Z");
        assertThat(FIXTURE.scheduleTimes()).allSatisfy(t -> assertThat(t).isAfter(first2027));
        assertThat(FIXTURE.eventTimes()).allSatisfy(
                t -> assertThat(t).isAfterOrEqualTo(B0FixtureGenerator.ALERT_START_AT));
    }
}
