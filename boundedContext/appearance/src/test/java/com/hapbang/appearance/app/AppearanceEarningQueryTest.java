package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.AppearanceEarningBucketResDto;
import com.hapbang.appearance.app.dto.AppearanceEarningItemResDto;
import com.hapbang.appearance.app.dto.AppearanceEarningSummaryResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.Appearance;
import com.hapbang.appearance.domain.AppearanceEarningStatus;
import com.hapbang.appearance.domain.AppearanceEarningType;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRepository;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.billing.domain.SettlementSourceType;

/**
 * 출연 수익 조회 (#301, ISSUE.md APR-39). 실제 흐름(보내기·수락·취소 요청·일방 취소·확인·불참·모집)으로 상태를 만들고
 * 요약·목록을 확인한다. 세션 역할은 흐름을 만들 인플루언서 + 조회할 내부 호출(INTERNAL) 둘 다로 둔다.
 * 시각은 테스트 시계를 실제 순서대로 돌린다 (방송 전 처리 → 방송 → 확인·불참).
 */
@AppearanceIntegrationTest
@WithMockUser(roles = {"INFLUENCER", "INTERNAL"})
class AppearanceEarningQueryTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long GUEST3 = 4L;
    private static final PageRequest ALL = PageRequest.of(0, 100);

    @Autowired
    AppearanceEarningFacade appearanceEarningFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRepository appearanceRepository;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, GUEST2, GUEST3)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    /** 요청서를 보내고 게스트가 수락해 성립한 출연 ID */
    private Long establish(Long guestId, long fee) {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, guestId,
                AppearanceRequestFixture.content(clock.now(), fee));
        return appearanceRequestFacade.accept(guestId, sent.id(), sent.version()).appearanceId();
    }

    private Appearance appearance(Long id) {
        return appearanceRepository.findById(id).orElseThrow();
    }

    private HpTask taskOf(Long appearanceId, HpTaskType type) {
        return hpTaskRepository.findAll().stream()
                .filter(task -> appearanceId.equals(task.getAppearanceId()) && task.getTaskType() == type)
                .findFirst().orElseThrow();
    }

    /** 방송이 끝나 확인 기간에 들어선다 (모든 출연의 방송 예정 일시 1시간 뒤로) */
    private void broadcastEnded(Long... appearanceIds) {
        for (Long id : appearanceIds) {
            clock.advanceTo(appearance(id).getScheduledAt().plusHours(1));
        }
    }

    private List<AppearanceEarningItemResDto> list(Long memberId, List<AppearanceEarningStatus> statuses,
                                                   AppearanceEarningType type) {
        return appearanceEarningFacade.find(memberId, statuses, type, ALL).getContent();
    }

    private static Map<Long, AppearanceEarningItemResDto> byId(List<AppearanceEarningItemResDto> items) {
        return items.stream().collect(Collectors.toMap(AppearanceEarningItemResDto::sourceId, item -> item));
    }

    private static AppearanceEarningBucketResDto bucket(long amount, long count) {
        return new AppearanceEarningBucketResDto(amount, count);
    }

    /** 한 게스트에게 지급 상태가 모두 다른 출연 9건 */
    private record AllStates(Long pending, Long onHold, Long feeDone, Long feeProcessing, Long feeFailed,
                             Long penaltyDone, Long penaltyProcessing, Long absent, Long agreed) {
    }

    private AllStates allStates() {
        Long pending = establish(GUEST, 20_000);
        Long onHold = establish(GUEST, 21_000);
        Long feeDone = establish(GUEST, 30_000);
        Long feeProcessing = establish(GUEST, 22_000);
        Long feeFailed = establish(GUEST, 23_000);
        Long penaltyDone = establish(GUEST, 30_000);
        Long penaltyProcessing = establish(GUEST, 30_000);
        Long absent = establish(GUEST, 30_000);
        Long agreed = establish(GUEST, 30_000);

        // 방송 전: 취소 요청, 일방 취소(정산 성공 / 정산 결과 모름), 합의 취소
        appearanceFacade.requestCancel(GUEST, onHold, "일정이 생겼습니다.");
        appearanceFacade.hostCancel(HOST, penaltyDone, false, null);
        BillingStubs.unavailable(billingClient);
        appearanceFacade.hostCancel(HOST, penaltyProcessing, false, null);
        BillingStubs.available(billingClient);
        appearanceFacade.requestCancel(GUEST, agreed, "일정이 생겼습니다.");
        appearanceFacade.acceptCancel(HOST, agreed);

        // 방송 뒤: 확인(지급 성공 / 결과 모름 / Billing 거절), 불참
        broadcastEnded(pending, feeDone, feeProcessing, feeFailed, absent);
        appearanceFacade.confirm(HOST, feeDone);
        BillingStubs.unavailable(billingClient);
        appearanceFacade.confirm(HOST, feeProcessing);
        BillingStubs.reject(billingClient, Rejection.WALLET_NOT_FOUND);
        appearanceFacade.confirm(HOST, feeFailed);
        BillingStubs.available(billingClient);
        appearanceFacade.markAbsent(HOST, absent, null);
        return new AllStates(pending, onHold, feeDone, feeProcessing, feeFailed, penaltyDone, penaltyProcessing,
                absent, agreed);
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("상태 분류")
    class Classify {

        @Test
        @DisplayName("요약: 아직 받지 못한 수익만 상태별로. 확인 대기 20,000 / 보류 21,000 / 처리 중 22,000 + 위약금 9,000 / 실패 23,000, "
                + "지급 예정(SCHEDULED)은 출연에 없어 0. 완료·불참·합의 취소는 빠진다")
        void summary() {
            allStates();

            AppearanceEarningSummaryResDto summary = appearanceEarningFacade.summarize(GUEST, null);

            assertThat(summary.confirmationPending()).isEqualTo(bucket(20_000, 1));
            assertThat(summary.scheduled()).isEqualTo(bucket(0, 0));
            assertThat(summary.onHold()).isEqualTo(bucket(21_000, 1));
            assertThat(summary.processing()).isEqualTo(bucket(22_000 + 9_000, 2));
            assertThat(summary.failed()).isEqualTo(bucket(23_000, 1));
        }

        @Test
        @DisplayName("목록: 출연마다 한 줄, 지급 상태·종류·금액. 일방 취소는 위약금 한 줄만(출연료와 중복 없음), 원천은 Billing 지급 요청과 같은 값, 지급 예정 시각은 null")
        void items() {
            AllStates s = allStates();

            Map<Long, AppearanceEarningItemResDto> items = byId(list(GUEST, List.of(AppearanceEarningStatus.values()),
                    null));

            assertThat(items).hasSize(9);
            assertThat(items.values()).extracting(AppearanceEarningItemResDto::sourceId, AppearanceEarningItemResDto::status,
                    AppearanceEarningItemResDto::earningType, AppearanceEarningItemResDto::amountHp)
                    .containsExactlyInAnyOrder(
                            tuple(s.pending(), AppearanceEarningStatus.CONFIRMATION_PENDING, AppearanceEarningType.FEE, 20_000L),
                            tuple(s.onHold(), AppearanceEarningStatus.ON_HOLD, AppearanceEarningType.FEE, 21_000L),
                            tuple(s.feeDone(), AppearanceEarningStatus.COMPLETED, AppearanceEarningType.FEE, 30_000L),
                            tuple(s.feeProcessing(), AppearanceEarningStatus.PROCESSING, AppearanceEarningType.FEE, 22_000L),
                            tuple(s.feeFailed(), AppearanceEarningStatus.FAILED, AppearanceEarningType.FEE, 23_000L),
                            tuple(s.penaltyDone(), AppearanceEarningStatus.COMPLETED, AppearanceEarningType.PENALTY, 9_000L),
                            tuple(s.penaltyProcessing(), AppearanceEarningStatus.PROCESSING, AppearanceEarningType.PENALTY,
                                    9_000L),
                            tuple(s.absent(), AppearanceEarningStatus.CANCELED, AppearanceEarningType.FEE, 30_000L),
                            tuple(s.agreed(), AppearanceEarningStatus.CANCELED, AppearanceEarningType.FEE, 30_000L));
            assertThat(items.values()).allSatisfy(item -> {
                assertThat(item.sourceType()).isEqualTo(SettlementSourceType.APPEARANCE_REQUEST);
                assertThat(item.title()).isEqualTo(AppearanceRequestFixture.content(clock.now()).title());
                assertThat(item.payoutScheduledAt()).isNull();
            });
            // 원천 ID = Billing 지급·위약금 요청에 보낸 원천 ID (출연 ID)
            assertThat(taskOf(s.feeDone(), HpTaskType.PAYOUT).getAppearanceId()).isEqualTo(s.feeDone());
            assertThat(taskOf(s.penaltyDone(), HpTaskType.PENALTY).getAppearanceId()).isEqualTo(s.penaltyDone());
        }

        @Test
        @DisplayName("상태 기준 시각: 완료·실패는 작업 완료 시각, 처리 중은 확인·일방 취소 시각, 취소는 불참·합의 취소 시각, 확인 대기·보류는 출연 수정 시각")
        void statusUpdatedAt() {
            AllStates s = allStates();

            Map<Long, AppearanceEarningItemResDto> items = byId(list(GUEST, List.of(AppearanceEarningStatus.values()),
                    null));

            assertThat(items.get(s.feeDone()).statusUpdatedAt())
                    .isEqualTo(taskOf(s.feeDone(), HpTaskType.PAYOUT).getCompletedAt());
            assertThat(items.get(s.feeFailed()).statusUpdatedAt())
                    .isEqualTo(taskOf(s.feeFailed(), HpTaskType.PAYOUT).getCompletedAt()).isNotNull();
            assertThat(items.get(s.penaltyDone()).statusUpdatedAt())
                    .isEqualTo(taskOf(s.penaltyDone(), HpTaskType.PENALTY).getCompletedAt());
            assertThat(items.get(s.feeProcessing()).statusUpdatedAt()).isEqualTo(appearance(s.feeProcessing()).getConfirmedAt());
            assertThat(items.get(s.penaltyProcessing()).statusUpdatedAt())
                    .isEqualTo(appearance(s.penaltyProcessing()).getCanceledAt());
            assertThat(items.get(s.absent()).statusUpdatedAt()).isEqualTo(appearance(s.absent()).getAbsentAt());
            assertThat(items.get(s.agreed()).statusUpdatedAt()).isEqualTo(appearance(s.agreed()).getCanceledAt());
            assertThat(items.get(s.pending()).statusUpdatedAt()).isEqualTo(appearance(s.pending()).getUpdatedAt());
            assertThat(items.get(s.onHold()).statusUpdatedAt()).isEqualTo(appearance(s.onHold()).getUpdatedAt());
        }

        @Test
        @DisplayName("확인·일방 취소는 Billing 결과를 몰라도(무응답) 지급·위약금 작업이 함께 기록된다 (작업 없는 확인 건은 흐름상 생기지 않음)")
        void taskAlwaysRecorded() {
            AllStates s = allStates();

            for (Long id : List.of(s.feeDone(), s.feeProcessing(), s.feeFailed())) {
                assertThat(taskOf(id, HpTaskType.PAYOUT)).isNotNull();
            }
            for (Long id : List.of(s.penaltyDone(), s.penaltyProcessing())) {
                assertThat(taskOf(id, HpTaskType.PENALTY).getPayoutAmount()).isEqualTo(9_000L);
            }
        }

        @Test
        @DisplayName("있어야 할 지급 작업이 없는 확인 건(데이터 이상)은 숨기지 않고 FAILED로 요약·목록에 나온다 (N2)")
        void missingTaskIsFailed() {
            Long appearanceId = establish(GUEST, 30_000);
            broadcastEnded(appearanceId);
            appearanceFacade.confirm(HOST, appearanceId);
            jdbcTemplate.update("delete from appearances.hp_tasks where appearance_id = ?", appearanceId); // 데이터 이상 재현

            assertThat(appearanceEarningFacade.summarize(GUEST, null).failed()).isEqualTo(bucket(30_000, 1));
            assertThat(list(GUEST, null, null)).singleElement().satisfies(item -> {
                assertThat(item.status()).isEqualTo(AppearanceEarningStatus.FAILED);
                assertThat(item.statusUpdatedAt()).isEqualTo(appearance(appearanceId).getConfirmedAt());
            });
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("필터")
    class Filter {

        @Test
        @DisplayName("상태를 고르지 않으면 아직 받지 못한 수익만 (요약과 같은 범위). 완료·취소는 고를 때만 나온다")
        void defaultUnpaid() {
            AllStates s = allStates();

            assertThat(list(GUEST, null, null)).extracting(AppearanceEarningItemResDto::sourceId)
                    .containsExactlyInAnyOrder(s.pending(), s.onHold(), s.feeProcessing(), s.feeFailed(),
                            s.penaltyProcessing());
            assertThat(list(GUEST, List.of(AppearanceEarningStatus.COMPLETED), null))
                    .extracting(AppearanceEarningItemResDto::sourceId).containsExactlyInAnyOrder(s.feeDone(), s.penaltyDone());
            assertThat(list(GUEST, List.of(AppearanceEarningStatus.CANCELED), null))
                    .extracting(AppearanceEarningItemResDto::sourceId).containsExactlyInAnyOrder(s.absent(), s.agreed());
        }

        @Test
        @DisplayName("종류 필터: 위약금만 고르면 요약도 위약금만 (처리 중 9,000 1건), 목록은 미지급 위약금 1건")
        void byType() {
            AllStates s = allStates();

            AppearanceEarningSummaryResDto penalty = appearanceEarningFacade.summarize(GUEST, AppearanceEarningType.PENALTY);
            assertThat(penalty.processing()).isEqualTo(bucket(9_000, 1));
            assertThat(penalty.confirmationPending()).isEqualTo(bucket(0, 0));
            assertThat(penalty.failed()).isEqualTo(bucket(0, 0));
            assertThat(list(GUEST, null, AppearanceEarningType.PENALTY)).extracting(AppearanceEarningItemResDto::sourceId)
                    .containsExactly(s.penaltyProcessing());
            assertThat(list(GUEST, null, AppearanceEarningType.FEE)).extracting(AppearanceEarningItemResDto::sourceId)
                    .containsExactlyInAnyOrder(s.pending(), s.onHold(), s.feeProcessing(), s.feeFailed());
        }

        @Test
        @DisplayName("빈 상태 목록(출연에 없는 공통 상태만 고른 경우, 예: SCHEDULED)이나 맞는 조합이 없는 필터는 빈 결과 (N4)")
        void noMatch() {
            allStates();

            assertThat(list(GUEST, List.of(), null)).isEmpty();
            assertThat(list(GUEST, List.of(AppearanceEarningStatus.CONFIRMATION_PENDING), AppearanceEarningType.PENALTY))
                    .isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("대상 회원")
    class Member {

        @Test
        @DisplayName("모집 1:N: 성립한 게스트마다 1인 출연료 한 건(원천 RECRUIT_APPEARANCE, 제목은 모집글), 미선정 게스트는 없음, 모집글 보류 전체는 세지 않는다")
        void recruitPerGuest() {
            Long recruitId = appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(clock.now())).id();
            Long first = appearanceApplicationFacade.accept(HOST,
                    appearanceApplicationFacade.apply(GUEST, recruitId, "지원합니다.").id()).appearanceId();
            Long second = appearanceApplicationFacade.accept(HOST,
                    appearanceApplicationFacade.apply(GUEST2, recruitId, "지원합니다.").id()).appearanceId();
            appearanceApplicationFacade.apply(GUEST3, recruitId, "지원합니다.");
            appearanceRecruitFacade.closeEarly(HOST, recruitId); // 대기 지원 미선정, 빈자리 반환

            for (Long[] guestAndAppearance : new Long[][]{{GUEST, first}, {GUEST2, second}}) {
                assertThat(list(guestAndAppearance[0], null, null)).singleElement().satisfies(item -> {
                    assertThat(item.sourceId()).isEqualTo(guestAndAppearance[1]);
                    assertThat(item.sourceType()).isEqualTo(SettlementSourceType.RECRUIT_APPEARANCE);
                    assertThat(item.amountHp()).isEqualTo(AppearanceRecruitFixture.FEE_PER_PERSON);
                    assertThat(item.title()).isEqualTo(AppearanceRecruitFixture.content(clock.now()).title());
                });
                assertThat(appearanceEarningFacade.summarize(guestAndAppearance[0], null).confirmationPending())
                        .isEqualTo(bucket(AppearanceRecruitFixture.FEE_PER_PERSON, 1));
            }
            assertThat(list(GUEST3, List.of(AppearanceEarningStatus.values()), null)).isEmpty();
        }

        @Test
        @DisplayName("다른 회원의 수익은 나오지 않는다: 호스트로 조회하면 비어 있고(호스트는 수익을 받는 쪽이 아님), 출연이 없는 회원은 0원 요약·빈 목록")
        void onlyThatGuest() {
            allStates();

            assertThat(list(HOST, List.of(AppearanceEarningStatus.values()), null)).isEmpty();
            assertThat(list(GUEST2, null, null)).isEmpty();
            AppearanceEarningSummaryResDto unknown = appearanceEarningFacade.summarize(999L, null);
            assertThat(List.of(unknown.confirmationPending(), unknown.scheduled(), unknown.onHold(), unknown.processing(),
                    unknown.failed())).containsOnly(bucket(0, 0));
        }

        @Test
        @DisplayName("회원 ID가 없거나 1 미만이면 INVALID_REQUEST")
        void invalidMemberId() {
            for (Long memberId : new Long[]{null, 0L, -1L}) {
                assertThatThrownBy(() -> appearanceEarningFacade.summarize(memberId, null))
                        .isInstanceOf(AppearanceException.class)
                        .extracting("errorCode").isEqualTo(AppearanceErrorCode.INVALID_REQUEST);
                assertThatThrownBy(() -> appearanceEarningFacade.find(memberId, null, null, ALL))
                        .isInstanceOf(AppearanceException.class)
                        .extracting("errorCode").isEqualTo(AppearanceErrorCode.INVALID_REQUEST);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("페이지·정렬")
    class Paging {

        @Test
        @DisplayName("요약 합계 = 기본 목록을 끝까지 넘긴 합계, 페이지끼리 중복·누락 없음 (2건씩)")
        void summaryMatchesAllPages() {
            allStates();
            AppearanceEarningSummaryResDto summary = appearanceEarningFacade.summarize(GUEST, null);
            long summaryAmount = summary.confirmationPending().amountHp() + summary.onHold().amountHp()
                    + summary.processing().amountHp() + summary.failed().amountHp();
            long summaryCount = summary.confirmationPending().count() + summary.onHold().count()
                    + summary.processing().count() + summary.failed().count();

            List<AppearanceEarningItemResDto> collected = new ArrayList<>();
            Page<AppearanceEarningItemResDto> page = appearanceEarningFacade.find(GUEST, null, null, PageRequest.of(0, 2));
            collected.addAll(page.getContent());
            while (page.hasNext()) {
                page = appearanceEarningFacade.find(GUEST, null, null, page.nextPageable());
                collected.addAll(page.getContent());
            }

            assertThat(page.getTotalElements()).isEqualTo(summaryCount);
            assertThat(collected).hasSize((int) summaryCount);
            assertThat(collected.stream().map(AppearanceEarningItemResDto::sourceId).collect(Collectors.toSet()))
                    .hasSize((int) summaryCount);
            assertThat(collected.stream().mapToLong(AppearanceEarningItemResDto::amountHp).sum()).isEqualTo(summaryAmount);
        }

        @Test
        @DisplayName("정렬: 상태 기준 시각 내림차순, 같은 시각이면 출연 ID 내림차순 — 같은 시각이 페이지 경계에 걸려도 중복·누락 없음")
        void stableOrder() {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ids.add(establish(GUEST, 30_000));
            }
            // 네 건의 상태 기준 시각(출연 수정 시각)을 같게 만든다: 정렬 동점 재현
            LocalDateTime same = clock.now().withNano(0);
            jdbcTemplate.update("update appearances.appearances set updated_at = ? where guest_id = ?", same, GUEST);

            List<Long> firstPage = appearanceEarningFacade.find(GUEST, null, null, PageRequest.of(0, 2)).getContent()
                    .stream().map(AppearanceEarningItemResDto::sourceId).toList();
            List<Long> secondPage = appearanceEarningFacade.find(GUEST, null, null, PageRequest.of(1, 2)).getContent()
                    .stream().map(AppearanceEarningItemResDto::sourceId).toList();

            List<Long> expected = ids.stream().sorted((a, b) -> Long.compare(b, a)).toList();
            assertThat(firstPage).containsExactlyElementsOf(expected.subList(0, 2));
            assertThat(secondPage).containsExactlyElementsOf(expected.subList(2, 4));
            Set<Long> union = new HashSet<>(firstPage);
            union.addAll(secondPage);
            assertThat(union).containsExactlyInAnyOrderElementsOf(ids);
        }

        @Test
        @DisplayName("상태 기준 시각이 다르면 최근 것이 먼저: 나중에 확인한 출연이 위")
        void latestFirst() {
            Long earlier = establish(GUEST, 30_000);
            Long later = establish(GUEST, 30_000);
            broadcastEnded(earlier, later);
            BillingStubs.unavailable(billingClient);
            appearanceFacade.confirm(HOST, earlier);
            clock.advance(Duration.ofMinutes(5));
            appearanceFacade.confirm(HOST, later);
            BillingStubs.available(billingClient);

            assertThat(list(GUEST, List.of(AppearanceEarningStatus.PROCESSING), null))
                    .extracting(AppearanceEarningItemResDto::sourceId).containsExactly(later, earlier);
        }
    }

    @Test
    @WithMockUser(roles = "INFLUENCER")
    @DisplayName("내부 호출(ROLE_INTERNAL)이 아니면 막힌다: 회원 세션(인플루언서)으로는 요약·목록을 부를 수 없다")
    void internalOnly() {
        assertThatThrownBy(() -> appearanceEarningFacade.summarize(GUEST, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceEarningFacade.find(GUEST, null, null, ALL))
                .isInstanceOf(AccessDeniedException.class);
    }
}
