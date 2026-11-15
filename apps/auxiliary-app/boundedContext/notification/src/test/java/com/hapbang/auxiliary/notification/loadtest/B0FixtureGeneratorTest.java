package com.hapbang.auxiliary.notification.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.producer.internals.BuiltInPartitioner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.hapbang.auxiliary.notification.app.NotificationTemplate;
import com.hapbang.auxiliary.notification.app.NotificationTemplates;

/**
 * B0 고정 이벤트 생성기(N13, ND-156) 시험. test-spec NT-B0-01~07(G1~G4·G6·G7). 기대 결과는 카탈로그(
 * {@link B0EventType})가 문서(notification-catalog·ND)로 정한 규칙으로 계산하고, 구현 코드를 보고 맞추지 않는다.
 */
class B0FixtureGeneratorTest {

    private static final long SEED = B0FixtureGenerator.DEFAULT_SEED;
    private static final B0Fixture FIXTURE = B0FixtureGenerator.generate(SEED);

    // G1 재현성

    @Test
    void 같은_seed면_같은_파일과_manifest가_나오고_다른_seed면_다르다() {
        B0Fixture again = B0FixtureGenerator.generate(SEED);
        B0Fixture other = B0FixtureGenerator.generate(SEED + 1);

        assertThat(again.contentHash()).isEqualTo(FIXTURE.contentHash());
        assertThat(again.manifest()).isEqualTo(FIXTURE.manifest());
        assertThat(other.contentHash()).isNotEqualTo(FIXTURE.contentHash());
    }

    // G9 두 단계 투입(ND-166): 정상 단계로 성능을 재고, 실패 단계(복제본 없음·깨진 본문)로 DLT·재시도를 본다

    @Test
    void 실패_단계_레코드는_모든_토픽에서_정상_단계_레코드_뒤에_있고_단계별_건수를_manifest에_적는다() {
        List<B0Record> alerts = FIXTURE.alerts();
        int firstFailure = 0;
        while (firstFailure < alerts.size() && !alerts.get(firstFailure).category().failurePhase()) {
            firstFailure++;
        }
        assertThat(alerts.subList(firstFailure, alerts.size())).hasSize(75)
                .allSatisfy(r -> assertThat(r.category().failurePhase()).isTrue());
        Map<String, Integer> failureByTopic = alerts.subList(firstFailure, alerts.size()).stream()
                .collect(Collectors.groupingBy(B0Record::topic, Collectors.summingInt(r -> 1)));
        assertThat(FIXTURE.manifest().phaseRecordsByTopic().get("failure")).isEqualTo(failureByTopic);
        assertThat(FIXTURE.manifest().phaseRecordsByTopic().get("normal").values().stream().mapToInt(i -> i).sum())
                .isEqualTo(4_925);
    }

    @Test
    void 단계를_나눠도_레코드와_기대_결과와_순서_의존_관계는_한_단계일_때와_같다() {
        B0Fixture single = B0FixtureGenerator.generate(SEED, false);
        B0Manifest phased = FIXTURE.manifest();
        B0Manifest unphased = single.manifest();

        assertThat(FIXTURE.members()).isEqualTo(single.members());
        assertThat(FIXTURE.alerts()).containsExactlyInAnyOrderElementsOf(single.alerts());
        assertThat(phased.recordsByTopic()).isEqualTo(unphased.recordsByTopic());
        assertThat(phased.outcomes()).isEqualTo(unphased.outcomes());
        assertThat(phased.deadLetters()).isEqualTo(unphased.deadLetters());
        assertThat(phased.failures()).isEqualTo(unphased.failures());
        assertThat(phased.legacySkipped()).isEqualTo(unphased.legacySkipped());
        assertThat(phased.notAlertSkipped()).isEqualTo(unphased.notAlertSkipped());
        assertThat(phased.schedules()).isEqualTo(unphased.schedules());
        assertThat(phased.notificationRows()).isEqualTo(unphased.notificationRows());
        assertThat(phased.rowsByReceiver()).isEqualTo(unphased.rowsByReceiver());
        assertThat(phased.inactiveReceivers()).isEqualTo(unphased.inactiveReceivers());
        assertThat(phased.coverage()).isEqualTo(unphased.coverage());
        assertThat(phased.orderIndependentPairs()).isEqualTo(unphased.orderIndependentPairs());
        assertThat(relations(phased)).isEqualTo(relations(unphased)).hasSize(400);
        // 같은 파티션 안에서 정상 레코드끼리의 순서는 그대로다(실패 레코드만 뒤로 간다)
        assertThat(normalOnly(phased, FIXTURE)).isEqualTo(normalOnly(unphased, single));
    }

    private static List<String> relations(B0Manifest manifest) {
        return manifest.orderedPairs().stream().map(p -> p.relation() + "|" + p.firstTopic() + "|" + p.firstKey()
                + "|" + p.secondTopic() + "|" + p.secondKey()).sorted().toList();
    }

    private static Map<String, Map<Integer, List<String>>> normalOnly(B0Manifest manifest, B0Fixture fixture) {
        Set<String> failures = fixture.alerts().stream().filter(r -> r.category().failurePhase())
                .map(B0Record::digest).collect(Collectors.toSet());
        Map<String, Map<Integer, List<String>>> result = new java.util.TreeMap<>();
        manifest.partitionSequences().forEach((topic, partitions) -> partitions.forEach((partition, digests) ->
                result.computeIfAbsent(topic, k -> new java.util.TreeMap<>()).put(partition,
                        digests.stream().filter(d -> !failures.contains(d)).toList())));
        return result;
    }

    // G2 건수·비율·오류 유형

    @Test
    void 회원_1000건과_알림_5000건을_정한_비율과_오류_유형별_건수로_만든다() {
        assertThat(FIXTURE.members()).hasSize(1_000);
        assertThat(FIXTURE.alerts()).hasSize(5_000);
        Map<B0Category, Long> byCategory = FIXTURE.alerts().stream()
                .collect(Collectors.groupingBy(B0Record::category, Collectors.counting()));
        assertThat(byCategory).containsEntry(B0Category.NORMAL, 4_625L).containsEntry(B0Category.RESEND, 250L)
                .containsEntry(B0Category.LEGACY, 50L).containsEntry(B0Category.UNKNOWN_RECEIVER, 50L)
                .containsEntry(B0Category.BROKEN, 25L);
        Map<String, Long> normalByTopic = FIXTURE.alerts().stream().filter(r -> r.category() == B0Category.NORMAL)
                .collect(Collectors.groupingBy(B0Record::topic, Collectors.counting()));
        assertThat(normalByTopic).containsEntry(B0Topics.BILLING, 1_850L).containsEntry(B0Topics.APPEARANCE, 1_388L)
                .containsEntry(B0Topics.LECTURE, 925L).containsEntry(B0Topics.CUSTOMERSUPPORT, 231L)
                .containsEntry(B0Topics.CHAT, 231L);
    }

    @Test
    void 오류_유형은_서로_겹치지_않고_재전송은_정상_이벤트를_그대로_한_번_더_보낸다() {
        Set<String> normalLines = FIXTURE.alerts().stream().filter(r -> r.category() == B0Category.NORMAL)
                .map(B0Record::line).collect(Collectors.toSet());
        List<B0Record> resends = FIXTURE.alerts().stream().filter(r -> r.category() == B0Category.RESEND).toList();
        assertThat(resends).allSatisfy(r -> assertThat(normalLines).contains(r.line()));
        assertThat(resends.stream().map(B0Record::line).distinct()).hasSize(250);
        // 오류 유형 레코드는 정상 레코드와 같은 줄이 아니다(따로 만든 이벤트)
        assertThat(FIXTURE.alerts().stream()
                .filter(r -> r.category() != B0Category.NORMAL && r.category() != B0Category.RESEND))
                .allSatisfy(r -> assertThat(normalLines).doesNotContain(r.line()));
        // 재전송은 원본 뒤에 온다(같은 key)
        List<String> lines = FIXTURE.alerts().stream().map(B0Record::line).toList();
        assertThat(resends).allSatisfy(r -> assertThat(lines.indexOf(r.line()))
                .isLessThan(lines.lastIndexOf(r.line())));
    }

    // G3 대표 이벤트·범위표

    @Test
    void 대표_이벤트가_만드는_알림_종류는_모두_등록된_템플릿이다() {
        NotificationTemplates templates = new NotificationTemplates(
                new StaticListableBeanFactory().getBeanProvider(NotificationTemplate.class));

        assertThat(B0EventType.alertKinds()).isNotEmpty().allSatisfy(kind ->
                assertThatCode(() -> templates.renderer(kind)).as(kind).doesNotThrowAnyException());
    }

    @Test
    void 리스너_5개와_회원_리스너의_분기와_예외_경로를_모두_지난다() {
        assertThat(FIXTURE.manifest().coverage()).containsOnlyKeys(B0Branch.values())
                .allSatisfy((branch, count) -> assertThat(count).as(branch.name()).isPositive());
    }

    // G4 시각

    @Test
    void 사건_시각은_알림_시작_시각_이후이고_예약의_기한과_보낼_시각은_2027년_이후다() {
        Instant start = B0FixtureGenerator.ALERT_START_AT;
        Instant floor = Instant.parse("2027-01-01T00:00:00Z");

        assertThat(FIXTURE.eventTimes()).allSatisfy(t -> assertThat(t).isAfterOrEqualTo(start));
        assertThat(FIXTURE.scheduleTimes()).isNotEmpty().allSatisfy(t -> assertThat(t).isAfterOrEqualTo(floor));
    }

    @Test
    void 가입_환영은_카탈로그대로_활성_회원마다_한_건이고_정지_탈퇴_회원은_받는_사람_제외다() {
        // notification-catalog USER_JOINED(새 회원, 원본 대상 user.id ND-073), 받는 사람 정책 ND-018. 실측을 옮기지 않고 회원 구성에서 센다
        long active = FIXTURE.members().stream()
                .filter(r -> ((com.hapbang.shared.member.event.UserJoinedEvent) r.event()).user().status()
                        == com.hapbang.shared.member.domain.UserStatus.ACTIVE)
                .count();
        B0Manifest manifest = FIXTURE.manifest();

        assertThat(active).isEqualTo(950);
        assertThat(manifest.outcomes().get(B0Topics.MEMBER))
                .containsExactlyInAnyOrderEntriesOf(Map.of("created", 950, "skipped_receiver", 50));
        assertThat(manifest.rowsByReceiver()).hasSize(950).doesNotContainKeys(
                manifest.inactiveReceivers().toArray(Long[]::new));
        assertThat(B0EventType.alertKinds()).contains("USER_JOINED");
    }

    // G6 보존

    @Test
    void 받을_수_있는_회원_중_20건을_넘게_받는_사람이_있다() {
        assertThat(FIXTURE.manifest().rowsByReceiver().values()).anySatisfy(rows -> assertThat(rows).isGreaterThan(20));
    }

    // G7 파티션과 순서

    @Test
    void 기대_파티션은_발행_앱과_같은_kafka_clients의_내장_파티셔너로_계산한다() {
        B0Manifest manifest = FIXTURE.manifest();
        for (B0Record record : FIXTURE.alerts()) {
            int expected = BuiltInPartitioner.partitionForKey(record.key().getBytes(StandardCharsets.UTF_8),
                    B0Topics.PARTITIONS);
            assertThat(record.partition()).isEqualTo(expected);
        }
        long total = manifest.partitionSequences().values().stream()
                .flatMap(byPartition -> byPartition.values().stream()).mapToLong(List::size).sum();
        assertThat(total).isEqualTo(5_000 + 1_000);
    }

    @Test
    void 순서_의존_쌍은_모두_같은_토픽과_같은_key이고_토픽_사이_의존_쌍은_없다() {
        List<B0Manifest.OrderedPair> pairs = FIXTURE.manifest().orderedPairs();

        assertThat(pairs).isNotEmpty().allSatisfy(pair -> {
            assertThat(pair.firstTopic()).isEqualTo(pair.secondTopic());
            assertThat(pair.firstKey()).isEqualTo(pair.secondKey());
        });
        List<B0Record> alerts = FIXTURE.alerts();
        assertThat(pairs).allSatisfy(pair -> {
            assertThat(pair.firstIndex()).isLessThan(pair.secondIndex());
            assertThat(alerts.get(pair.firstIndex()).key()).isEqualTo(pair.firstKey());
            assertThat(alerts.get(pair.secondIndex()).key()).isEqualTo(pair.secondKey());
        });
        // 다른 key(다른 파티션일 수 있음)의 생성·취소는 순서와 상관없이 결과가 같아야 하는 쌍으로만 둔다(ND-034·ND-138)
        assertThat(FIXTURE.manifest().orderIndependentPairs()).isNotEmpty()
                .allSatisfy(pair -> assertThat(pair.firstKey()).isNotEqualTo(pair.secondKey()));
    }
}
