package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Page;

import com.hapbang.billing.app.EarningSummaryResult.Amount;
import com.hapbang.billing.app.EarningSummaryResult.Buckets;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.EarningSourceType;
import com.hapbang.billing.domain.EarningStatus;
import com.hapbang.billing.domain.EarningType;

// 지급 예정 수익 통합 요약·목록(#295)의 합산, 전역 정렬·페이지, 깊이 제한, 한쪽 실패 시 전체 실패를 가짜 출처로 검증한다.
// 출처는 실제 내부 API처럼 (상태 변경 시각 desc, ID desc)로 정렬된 데이터를 page·size로 잘라 준다.
class EarningQueryUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-16T05:00:00.123456Z");
    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    // ---------------------------------------------------------------------------------------------
    // 요약
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("두 출처의 상태별 금액·건수를 더하고 전체 합계를 내며, asOf는 조회 시작 시각이다")
    void summary_both() {
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE)
                .summary(buckets(a(10_000, 1), a(20_000, 2), a(0, 0), a(5_000, 1), a(1_000, 1)));
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE)
                .summary(buckets(a(30_000, 1), a(0, 0), a(20_000, 1), a(0, 0), a(7_000, 2)));

        EarningSummaryResult result = useCase(lecture, appearance).summary(1L);

        assertThat(result.confirmationPending()).isEqualTo(a(40_000, 2));
        assertThat(result.scheduled()).isEqualTo(a(20_000, 2));
        assertThat(result.onHold()).isEqualTo(a(20_000, 1));
        assertThat(result.processing()).isEqualTo(a(5_000, 1));
        assertThat(result.failed()).isEqualTo(a(8_000, 3));
        assertThat(result.total()).isEqualTo(a(93_000, 9));
        assertThat(result.asOf()).isEqualTo(NOW);
        assertThat(lecture.summaryCalls).isEqualTo(1);
        assertThat(appearance.summaryCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("강의만 있거나 출연만 있으면 그 출처 값이 그대로 합계이고, 둘 다 없으면 모두 0이다")
    void summary_oneSideOrNone() {
        Buckets only = buckets(a(1_000, 1), a(2_000, 1), a(3_000, 1), a(4_000, 1), a(5_000, 1));
        FakeSource empty = new FakeSource(EarningSourceType.APPEARANCE);

        EarningSummaryResult lectureOnly = useCase(new FakeSource(EarningSourceType.LECTURE).summary(only), empty)
                .summary(1L);
        EarningSummaryResult appearanceOnly = useCase(new FakeSource(EarningSourceType.LECTURE),
                new FakeSource(EarningSourceType.APPEARANCE).summary(only)).summary(1L);
        EarningSummaryResult none = useCase(new FakeSource(EarningSourceType.LECTURE), empty).summary(1L);

        for (EarningSummaryResult result : List.of(lectureOnly, appearanceOnly)) {
            assertThat(List.of(result.confirmationPending(), result.scheduled(), result.onHold(), result.processing(),
                    result.failed())).containsExactly(a(1_000, 1), a(2_000, 1), a(3_000, 1), a(4_000, 1), a(5_000, 1));
            assertThat(result.total()).isEqualTo(a(15_000, 5));
        }
        assertThat(List.of(none.confirmationPending(), none.scheduled(), none.onHold(), none.processing(),
                none.failed(), none.total())).containsOnly(a(0, 0));
        assertThat(none.asOf()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("한쪽 출처가 실패하면 요약 전체가 실패한다(부분 합계 없음)")
    void summary_failure() {
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE).summary(buckets(a(1, 1), a(0, 0), a(0, 0),
                a(0, 0), a(0, 0)));
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE).failing();

        assertThatThrownBy(() -> useCase(lecture, appearance).summary(1L))
                .isInstanceOfSatisfying(EarningSourceException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.EARNING_SOURCE_UNAVAILABLE);
                    assertThat(e.getSource()).isEqualTo(EarningSourceType.APPEARANCE);
                    assertThat(e.getProperties()).containsEntry("source", "APPEARANCE");
                });
        // 강의가 먼저 실패하면 출연은 부르지 않는다
        FakeSource failingLecture = new FakeSource(EarningSourceType.LECTURE).failing();
        FakeSource untouched = new FakeSource(EarningSourceType.APPEARANCE);
        assertThatThrownBy(() -> useCase(failingLecture, untouched).summary(1L))
                .isInstanceOf(EarningSourceException.class);
        assertThat(untouched.summaryCalls).isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // 목록: 전역 정렬
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("두 출처를 상태 변경 시각 최신순으로 섞고, 같은 시각이면 sourceId 내림차순 → sourceType → earningType 오름차순이다")
    void list_globalOrder() {
        Instant t = BASE.plusSeconds(100);
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE).items(
                item(EarningSourceType.LECTURE, 9L, EarningType.LECTURE_SALE, t.plusSeconds(50)),
                item(EarningSourceType.LECTURE, 7L, EarningType.LECTURE_SALE, t),
                item(EarningSourceType.LECTURE, 5L, EarningType.LECTURE_SALE, t),
                item(EarningSourceType.LECTURE, 3L, EarningType.LECTURE_SALE, t.minusSeconds(1)));
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE).items(
                item(EarningSourceType.APPEARANCE, 8L, EarningType.APPEARANCE_FEE, t.plusSeconds(10)),
                item(EarningSourceType.APPEARANCE, 7L, EarningType.APPEARANCE_PENALTY, t),
                item(EarningSourceType.APPEARANCE, 6L, EarningType.APPEARANCE_FEE, t),
                item(EarningSourceType.APPEARANCE, 1L, EarningType.APPEARANCE_FEE, t.minusSeconds(1)));

        Page<EarningItemResult> page = useCase(lecture, appearance).list(1L, null, 0, 20);

        assertThat(page.getContent()).extracting(this::key).containsExactly(
                "LECTURE:9", "APPEARANCE:8",
                // 같은 시각 t: ID 7 두 건은 sourceType 이름순(APPEARANCE < LECTURE), 그다음 6, 5
                "APPEARANCE:7", "LECTURE:7", "APPEARANCE:6", "LECTURE:5",
                // 같은 시각 t-1: ID 내림차순
                "LECTURE:3", "APPEARANCE:1");
        assertThat(page.getTotalElements()).isEqualTo(8);
        assertThat(page.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("시각·sourceId·sourceType이 모두 같으면 earningType 이름 오름차순이다")
    void order_earningTypeTieBreak() {
        Instant t = BASE;
        List<EarningItemResult> items = new ArrayList<>(List.of(
                item(EarningSourceType.APPEARANCE, 7L, EarningType.APPEARANCE_PENALTY, t),
                item(EarningSourceType.APPEARANCE, 7L, EarningType.APPEARANCE_FEE, t)));

        items.sort(EarningItemResult.ORDER);

        assertThat(items).extracting(EarningItemResult::earningType)
                .containsExactly(EarningType.APPEARANCE_FEE, EarningType.APPEARANCE_PENALTY);
    }

    // ---------------------------------------------------------------------------------------------
    // 목록: 전역 페이지
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("모든 페이지를 이어 붙이면 두 출처 전체를 전역 순서로 정렬한 것과 같다(여러 출처에 걸친 페이지 포함)")
    void list_pagesEqualFullMerge() {
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE).items(series(EarningSourceType.LECTURE, 37, 3));
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE)
                .items(series(EarningSourceType.APPEARANCE, 23, 5));
        List<EarningItemResult> expected = new ArrayList<>();
        expected.addAll(lecture.data);
        expected.addAll(appearance.data);
        expected.sort(EarningItemResult.ORDER);

        for (int size : List.of(1, 7, 20, 60)) {
            List<EarningItemResult> collected = new ArrayList<>();
            EarningQueryUseCase useCase = useCase(lecture, appearance);
            for (int page = 0; (long) (page + 1) * size <= EarningQueryUseCase.MAX_DEPTH; page++) {
                Page<EarningItemResult> result = useCase.list(1L, null, page, size);
                assertThat(result.getTotalElements()).isEqualTo(60);
                assertThat(result.getTotalPages()).isEqualTo((60 + size - 1) / size);
                assertThat(result.getSize()).isEqualTo(size);
                assertThat(result.getNumber()).isEqualTo(page);
                if (result.getContent().isEmpty()) {
                    break;
                }
                collected.addAll(result.getContent());
            }
            assertThat(collected).as("size %d", size).containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("출처마다 100건을 넘어(강의 135·출연 125) 내부 API 여러 페이지를 받아도, 모든 전역 페이지가 전체를 한 번에 정렬한 결과와 같다")
    void list_over100PerSource() {
        // 시각이 두 출처 사이에서 섞이고(강의 id/2초, 출연 5·id/9초), ID가 1..125에서 겹치며, 같은 시각·같은 ID도 생긴다
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE).items(IntStream.rangeClosed(1, 135)
                .mapToObj(id -> item(EarningSourceType.LECTURE, id, EarningType.LECTURE_SALE, BASE.plusSeconds(id / 2)))
                .toArray(EarningItemResult[]::new));
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE).items(IntStream.rangeClosed(1, 125)
                .mapToObj(id -> item(EarningSourceType.APPEARANCE, id, EarningType.APPEARANCE_FEE,
                        BASE.plusSeconds(id * 5L / 9)))
                .toArray(EarningItemResult[]::new));
        List<EarningItemResult> expected = new ArrayList<>();
        expected.addAll(lecture.data);
        expected.addAll(appearance.data);
        expected.sort(EarningItemResult.ORDER);
        // 데이터 조건 확인: 출처 사이 같은 시각·같은 ID(1, 3, 5, 7), 같은 시각·다른 ID가 있다
        assertThat(lecture.data).anySatisfy(l -> assertThat(appearance.data).anySatisfy(a -> {
            assertThat(a.sourceId()).isEqualTo(l.sourceId());
            assertThat(a.statusUpdatedAt()).isEqualTo(l.statusUpdatedAt());
        }));
        assertThat(lecture.data).anySatisfy(l -> assertThat(appearance.data).anySatisfy(a -> {
            assertThat(a.sourceId()).isNotEqualTo(l.sourceId());
            assertThat(a.statusUpdatedAt()).isEqualTo(l.statusUpdatedAt());
        }));
        EarningQueryUseCase useCase = useCase(lecture, appearance);

        // page=7, size=20 → N = 160: 두 출처 모두 0·1페이지(100건씩)를 받고, 두 번째 페이지에서 끝난다(강의 35건, 출연 25건)
        Page<EarningItemResult> page7 = useCase.list(1L, null, 7, 20);
        assertThat(page7.getContent()).containsExactlyElementsOf(expected.subList(140, 160));
        assertThat(page7.getTotalElements()).isEqualTo(260);
        assertThat(page7.getTotalPages()).isEqualTo(13);
        assertThat(lecture.calls).containsExactly("0x100", "1x100");
        assertThat(appearance.calls).containsExactly("0x100", "1x100");
        // 이 페이지 안에서 출처가 바뀐다(전역 경계에서 두 출처가 섞인다)
        assertThat(page7.getContent()).extracting(EarningItemResult::sourceType)
                .contains(EarningSourceType.LECTURE, EarningSourceType.APPEARANCE);

        // 필요한 N건까지만 받는다: N = 100이면 출처마다 한 번, N = 120이면 두 번
        lecture.calls.clear();
        appearance.calls.clear();
        assertThat(useCase.list(1L, null, 4, 20).getContent()).containsExactlyElementsOf(expected.subList(80, 100));
        assertThat(lecture.calls).containsExactly("0x100");
        assertThat(appearance.calls).containsExactly("0x100");
        lecture.calls.clear();
        appearance.calls.clear();
        assertThat(useCase.list(1L, null, 5, 20).getContent()).containsExactlyElementsOf(expected.subList(100, 120));
        assertThat(lecture.calls).containsExactly("0x100", "1x100");
        assertThat(appearance.calls).containsExactly("0x100", "1x100");

        // 여러 페이지 크기로 모든 페이지를 이어 붙이면 중복·누락 없이 전체 정렬과 같다
        for (int size : List.of(1, 13, 20, 50, 100)) {
            lecture.calls.clear();
            appearance.calls.clear();
            List<EarningItemResult> collected = new ArrayList<>();
            int totalPages = (260 + size - 1) / size;
            for (int page = 0; page < totalPages; page++) {
                Page<EarningItemResult> result = useCase.list(1L, null, page, size);
                assertThat(result.getTotalElements()).isEqualTo(260);
                assertThat(result.getTotalPages()).isEqualTo(totalPages);
                assertThat(result.getContent()).hasSize(Math.min(size, 260 - page * size));
                collected.addAll(result.getContent());
            }
            assertThat(collected).as("size %d", size).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
            // 내부 API 한 번에 100건을 넘게 요청하지 않는다
            assertThat(lecture.calls).allSatisfy(call -> assertThat(Integer.parseInt(call.split("x")[1]))
                    .isLessThanOrEqualTo(EarningQueryUseCase.SOURCE_PAGE_SIZE));
            assertThat(appearance.calls).allSatisfy(call -> assertThat(Integer.parseInt(call.split("x")[1]))
                    .isLessThanOrEqualTo(EarningQueryUseCase.SOURCE_PAGE_SIZE));
            assertThat(lecture.calls).contains("1x100");
        }

        // 깊이 1,000 경계: N = 1,000은 범위 밖이라 빈 목록·전체 건수, N > 1,000은 출처를 부르지 않고 거절한다
        lecture.calls.clear();
        Page<EarningItemResult> deepest = useCase.list(1L, null, 49, 20);
        assertThat(deepest.getContent()).isEmpty();
        assertThat(deepest.getTotalElements()).isEqualTo(260);
        assertThat(lecture.calls).containsExactly("0x100", "1x100");
        lecture.calls.clear();
        assertThatThrownBy(() -> useCase.list(1L, null, 50, 20))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.EARNING_PAGE_TOO_DEEP));
        assertThat(lecture.calls).isEmpty();
    }

    @Test
    @DisplayName("한쪽이 비었거나 다른 쪽보다 짧아도 정확하고, 범위 밖 페이지는 빈 목록에 전체 건수를 준다")
    void list_emptyOrShortSide() {
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE).items(series(EarningSourceType.LECTURE, 5, 2));
        FakeSource empty = new FakeSource(EarningSourceType.APPEARANCE);

        Page<EarningItemResult> first = useCase(lecture, empty).list(1L, null, 0, 3);
        Page<EarningItemResult> second = useCase(lecture, empty).list(1L, null, 1, 3);
        Page<EarningItemResult> beyond = useCase(lecture, empty).list(1L, null, 4, 3);

        assertThat(first.getContent()).extracting(EarningItemResult::sourceId).containsExactly(5L, 4L, 3L);
        assertThat(second.getContent()).extracting(EarningItemResult::sourceId).containsExactly(2L, 1L);
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(5);

        FakeSource none = new FakeSource(EarningSourceType.LECTURE);
        Page<EarningItemResult> nothing = useCase(none, empty).list(1L, null, 0, 20);
        assertThat(nothing.getContent()).isEmpty();
        assertThat(nothing.getTotalElements()).isZero();
        assertThat(nothing.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("출처에서 앞쪽 N=(page+1)×size건까지만, 내부 API 최대 크기 100으로 0페이지부터 받고 출처가 끝나면 멈춘다")
    void list_sourceCalls() {
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE).items(series(EarningSourceType.LECTURE, 450, 1));
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE)
                .items(series(EarningSourceType.APPEARANCE, 30, 1));

        useCase(lecture, appearance).list(1L, null, 0, 20);
        assertThat(lecture.calls).containsExactly("0x20");
        assertThat(appearance.calls).containsExactly("0x20");

        lecture.calls.clear();
        appearance.calls.clear();
        // N = 13 × 20 = 260 → 강의 100·100·100(마지막 60건은 버린다), 출연은 첫 페이지에서 끝
        useCase(lecture, appearance).list(1L, null, 12, 20);
        assertThat(lecture.calls).containsExactly("0x100", "1x100", "2x100");
        assertThat(appearance.calls).containsExactly("0x100");
    }

    @Test
    @DisplayName("size=100, N=1,000은 허용하고 N>1,000은 출처를 부르지 않고 EARNING_PAGE_TOO_DEEP이다(정수 넘침 없음)")
    void list_depthLimit() {
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE).items(series(EarningSourceType.LECTURE, 1_200, 1));
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE);
        EarningQueryUseCase useCase = useCase(lecture, appearance);

        Page<EarningItemResult> last = useCase.list(1L, null, 9, 100);
        assertThat(last.getContent()).hasSize(100);
        assertThat(last.getContent().getFirst().sourceId()).isEqualTo(1_200L - 900);
        assertThat(last.getTotalElements()).isEqualTo(1_200);
        assertThat(useCase.list(1L, null, 49, 20).getContent()).hasSize(20);
        assertThat(useCase.list(1L, null, 0, 100).getContent()).hasSize(100);

        lecture.calls.clear();
        for (int[] pageSize : new int[][] {{10, 100}, {0, 1_001}, {50, 20}, {Integer.MAX_VALUE, 100},
                {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            assertThatThrownBy(() -> useCase.list(1L, null, pageSize[0], pageSize[1]))
                    .as(Arrays.toString(pageSize))
                    .isInstanceOfSatisfying(BillingException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.EARNING_PAGE_TOO_DEEP));
        }
        assertThat(lecture.calls).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null", "''"})
    @DisplayName("상태를 주지 않거나 빈 값만 주면 미지급 다섯 상태로 조회한다")
    void list_defaultStatuses(String given) {
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE);
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE);
        Set<EarningStatus> statuses = given == null ? null : new java.util.HashSet<>(Arrays.asList((EarningStatus) null));

        useCase(lecture, appearance).list(1L, statuses, 0, 20);

        assertThat(lecture.requestedStatuses).isEqualTo(EarningStatus.UNPAID);
        assertThat(appearance.requestedStatuses).isEqualTo(EarningStatus.UNPAID);
        assertThat(EarningStatus.UNPAID).containsExactlyInAnyOrder(EarningStatus.CONFIRMATION_PENDING,
                EarningStatus.SCHEDULED, EarningStatus.ON_HOLD, EarningStatus.PROCESSING, EarningStatus.FAILED);
    }

    @Test
    @DisplayName("명시한 상태는 그대로 두 출처에 넘긴다")
    void list_explicitStatuses() {
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE);
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE);

        useCase(lecture, appearance).list(1L, Set.of(EarningStatus.COMPLETED, EarningStatus.FAILED), 0, 20);

        assertThat(lecture.requestedStatuses).containsExactlyInAnyOrder(EarningStatus.COMPLETED, EarningStatus.FAILED);
        assertThat(appearance.requestedStatuses).containsExactlyInAnyOrder(EarningStatus.COMPLETED,
                EarningStatus.FAILED);
    }

    @Test
    @DisplayName("한쪽 출처의 목록 조회가 실패하면 목록 전체가 실패한다(부분 목록 없음)")
    void list_failure() {
        FakeSource lecture = new FakeSource(EarningSourceType.LECTURE).items(series(EarningSourceType.LECTURE, 3, 1));
        FakeSource appearance = new FakeSource(EarningSourceType.APPEARANCE).failing();

        assertThatThrownBy(() -> useCase(lecture, appearance).list(1L, null, 0, 20))
                .isInstanceOfSatisfying(EarningSourceException.class,
                        e -> assertThat(e.getSource()).isEqualTo(EarningSourceType.APPEARANCE));
    }

    // ---------------------------------------------------------------------------------------------

    private EarningQueryUseCase useCase(EarningSource lecture, EarningSource appearance) {
        return new EarningQueryUseCase(List.of(lecture, appearance), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String key(EarningItemResult item) {
        return item.sourceType() + ":" + item.sourceId();
    }

    private static Amount a(long amountHp, long count) {
        return new Amount(amountHp, count);
    }

    private static Buckets buckets(Amount cp, Amount scheduled, Amount onHold, Amount processing, Amount failed) {
        return new Buckets(cp, scheduled, onHold, processing, failed);
    }

    private static EarningItemResult item(EarningSourceType type, long id, EarningType earningType, Instant at) {
        return new EarningItemResult(type, id, earningType, "제목" + id, 1_000L, EarningStatus.CONFIRMATION_PENDING,
                null, at);
    }

    // ID 1..count, 시각은 ID를 step으로 나눈 몫만큼 늘어 여러 건이 같은 시각을 공유한다(동률이 출처 사이에서도 생긴다)
    private static EarningItemResult[] series(EarningSourceType type, int count, int step) {
        EarningType earningType = type == EarningSourceType.LECTURE ? EarningType.LECTURE_SALE
                : EarningType.APPEARANCE_FEE;
        return IntStream.rangeClosed(1, count)
                .mapToObj(id -> item(type, id, earningType, BASE.plusSeconds(id / step)))
                .toArray(EarningItemResult[]::new);
    }

    // 실제 내부 API처럼 (상태 변경 시각 desc, ID desc)로 정렬된 데이터를 page·size로 잘라 주는 가짜 출처
    private static final class FakeSource implements EarningSource {

        private final EarningSourceType type;
        private final List<EarningItemResult> data = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private Buckets summary = Buckets.ZERO;
        private Set<EarningStatus> requestedStatuses;
        private boolean failing;
        private int summaryCalls;

        FakeSource(EarningSourceType type) {
            this.type = type;
        }

        FakeSource items(EarningItemResult... items) {
            data.addAll(List.of(items));
            data.sort(Comparator.comparing(EarningItemResult::statusUpdatedAt, Comparator.reverseOrder())
                    .thenComparing(EarningItemResult::sourceId, Comparator.reverseOrder()));
            return this;
        }

        FakeSource summary(Buckets value) {
            summary = value;
            return this;
        }

        FakeSource failing() {
            failing = true;
            return this;
        }

        @Override
        public EarningSourceType type() {
            return type;
        }

        @Override
        public Buckets summary(long userId) {
            summaryCalls++;
            failIfNeeded();
            return summary;
        }

        @Override
        public SourcePage earnings(long userId, Set<EarningStatus> statuses, int page, int size) {
            calls.add(page + "x" + size);
            requestedStatuses = statuses;
            failIfNeeded();
            int from = Math.min(page * size, data.size());
            int to = Math.min(from + size, data.size());
            return new SourcePage(data.subList(from, to), data.size());
        }

        private void failIfNeeded() {
            if (failing) {
                throw new EarningSourceException(BillingErrorCode.EARNING_SOURCE_UNAVAILABLE, type, null);
            }
        }
    }
}
