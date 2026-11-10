package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.HpReconciliationMismatch;
import com.hapbang.appearance.domain.HpReconciliationRule;
import com.hapbang.appearance.domain.HpReconciliationRun;
import com.hapbang.appearance.domain.HpReconciliationStatus;
import com.hapbang.appearance.domain.HpReconciliationTrigger;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.HpReconciliationCursorRepository;
import com.hapbang.appearance.out.HpReconciliationMismatchRepository;
import com.hapbang.appearance.out.HpReconciliationRunRepository;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.shared.time.AppearanceTime;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.billing.domain.BillingHoldStatus;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.BillingSettlementStatus;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.domain.PayoutType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.HoldQueryCommand;
import com.hapbang.shared.billing.dto.HoldStateDto;
import com.hapbang.shared.billing.dto.HoldStatePageDto;
import com.hapbang.shared.billing.out.BillingCallExceptions;
import com.hapbang.shared.billing.out.BillingCallResult;

/**
 * 서비스 간 HP 대사 (#581). 출연은 실제 흐름(보내기·수락·확인·취소·모집 마감)으로 만들고, 지갑은 Mockito 대역이 돌려주는 조회 결과로 만든다.
 * <ul>
 *     <li>지갑 기록은 테스트가 손으로 적는다(출연 코드로 계산하지 않음). 출연료 30,000, 위약금 30% = 9,000</li>
 *     <li>변경 시각(updated_at)은 실제 시각으로 남고 대사는 테스트 시계로 판단한다. 그래서 시계를 3시간 넘기면 진행 중 유예(2시간)를 지난다</li>
 *     <li>실제 HTTP 요청 모양은 공용 Client 계약 테스트(#577)가 본다. 출연 Client는 공용 Client에 그대로 넘긴다</li>
 * </ul>
 */
@AppearanceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@WithMockUser(roles = "INFLUENCER")
class AppearanceHpReconciliationTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long ADMIN = 900L;
    private static final long FEE = AppearanceRequestFixture.FEE;
    private static final long PENALTY = 9_000L;

    @Autowired
    HpReconciliationUseCase hpReconciliationUseCase;

    @Autowired
    HpReconciliationMetrics hpReconciliationMetrics;

    @Autowired
    HpReconciliationMetricsRestorer hpReconciliationMetricsRestorer;

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
    HpTaskRepository hpTaskRepository;

    @Autowired
    HpReconciliationRunRepository runRepository;

    @Autowired
    HpReconciliationMismatchRepository mismatchRepository;

    @Autowired
    HpReconciliationCursorRepository cursorRepository;

    @Autowired
    BillingClient billingClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    /** 지갑 대역의 기록: (참조 유형, ID) → 조회 결과. 없으면 "기록 없음" */
    private final Map<String, HoldStateDto> wallet = new HashMap<>();

    /** 지갑 보류 목록 대역이 돌려줄 변경된 보류 (참조 유형별 한 페이지) */
    private final Map<BillingReferenceType, List<HoldStateDto>> listed = new HashMap<>();

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, GUEST2)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
        stubWallet();
    }

    /**
     * 지갑 조회 대역. {@link BillingStubs#available}은 대역을 초기화하므로 그 뒤에는 다시 건다({@link #walletAvailableAgain}).
     * 덮어쓰는 stub은 {@code doReturn}·{@code doThrow}로 건다({@code when(...)}은 이 answer를 빈 인자로 한 번 실행한다)
     */
    private void stubWallet() {
        doAnswer(invocation -> {
            HoldQueryCommand command = invocation.getArgument(0);
            List<HoldStateDto> states = new ArrayList<>();
            for (Long id : command.referenceIds()) {
                states.add(wallet.getOrDefault(key(command.referenceType(), id), empty(command.referenceType(), id)));
            }
            return new BillingCallResult.Succeeded<>(states);
        }).when(billingClient).queryHolds(any());
        doAnswer(invocation -> {
            BillingReferenceType type = invocation.getArgument(0);
            Instant updatedAfter = invocation.getArgument(1);
            long afterId = invocation.getArgument(2);
            return new BillingCallResult.Succeeded<>(
                    new HoldStatePageDto(listed.getOrDefault(type, List.of()), updatedAfter, afterId, false));
        }).when(billingClient).listHolds(any(), any(), anyLong(), anyInt());
    }

    /** 실패 설정을 지우고(출연 요청 모두 성공) 지갑 조회 대역을 다시 건다 */
    private void walletAvailableAgain() {
        BillingStubs.available(billingClient);
        stubWallet();
    }

    // ---------------------------------------------------------------------------------------------
    // 준비: 출연

    private AppearanceRequestResDto sendRequest(Long guestId) {
        return appearanceRequestFacade.create(HOST, guestId, AppearanceRequestFixture.content(clock.now()));
    }

    /** 요청서를 수락해 성립한 출연 ID */
    private Long acceptRequest(AppearanceRequestResDto sent) {
        return appearanceRequestFacade.accept(sent.guestId(), sent.id(), sent.version()).appearanceId();
    }

    private Long postRecruit() {
        return appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(clock.now())).id();
    }

    private Long establishFromRecruit(Long recruitId, Long guestId) {
        Long applicationId = appearanceApplicationFacade.apply(guestId, recruitId, "지원합니다.").id();
        return appearanceApplicationFacade.accept(HOST, applicationId).appearanceId();
    }

    /** 방송 예정 일시가 지나 확인할 수 있는 시각으로 시계를 옮긴다 */
    private void passBroadcast() {
        clock.advanceTo(jdbcTemplate.queryForObject("select max(scheduled_at) from appearances.appearances",
                LocalDateTime.class).plusMinutes(1));
    }

    /**
     * 대상·출연·HP 작업의 마지막 변경 시각을 이 시각으로 맞춘다. 변경 시각(JPA Auditing)은 테스트 시계가 아니라 실제 시각으로 남아,
     * 시계를 앞당긴 테스트(방송 뒤 확인)에서는 모든 행이 비교 범위(7일) 밖의 오래된 행이 되기 때문이다
     */
    private void changedAt(LocalDateTime at) {
        for (String table : List.of("requests", "recruits", "appearances", "hp_tasks")) {
            jdbcTemplate.update("update appearances." + table + " set updated_at = ?", at);
        }
    }

    private void assertNoPendingTask() {
        assertThat(hpTaskRepository.findAll()).extracting(HpTask::getStatus).doesNotContain(HpTaskStatus.PENDING);
    }

    // ---------------------------------------------------------------------------------------------
    // 준비: 지갑 기록

    private static String key(BillingReferenceType type, Long id) {
        return type + ":" + id;
    }

    private static HoldStateDto.Amount hp(long total) {
        return new HoldStateDto.Amount(total, 0, total);
    }

    private static HoldStateDto empty(BillingReferenceType type, Long id) {
        return new HoldStateDto(type, id, null, null, List.of());
    }

    private static HoldStateDto.Hold hold(BillingHoldStatus status, Long payer, long original, long remaining,
                                          long released, long paid) {
        return new HoldStateDto.Hold(status, payer, hp(original), hp(remaining), hp(released), hp(paid), hp(0));
    }

    private static HoldStateDto.Settlement settlement(SettlementSourceType sourceType, Long appearanceId,
                                                      PayoutType payoutType, Long receiver, long amount) {
        return new HoldStateDto.Settlement(sourceType, appearanceId, payoutType, receiver, amount, amount,
                BillingSettlementStatus.COMPLETED);
    }

    private void walletRequest(Long requestId, HoldStateDto.Hold hold, HoldStateDto.Voided voided,
                               HoldStateDto.Settlement... settlements) {
        wallet.put(key(BillingReferenceType.APPEARANCE_REQUEST, requestId), new HoldStateDto(
                BillingReferenceType.APPEARANCE_REQUEST, requestId, hold, voided, List.of(settlements)));
    }

    private void walletRecruit(Long recruitId, HoldStateDto.Hold hold, HoldStateDto.Settlement... settlements) {
        wallet.put(key(BillingReferenceType.APPEARANCE_RECRUIT, recruitId), new HoldStateDto(
                BillingReferenceType.APPEARANCE_RECRUIT, recruitId, hold, null, List.of(settlements)));
    }

    // ---------------------------------------------------------------------------------------------
    // 결과

    /** 모든 변경이 진행 중 유예(2시간)보다 오래된(3시간 전) 상태에서 배치로 실행한다 */
    private HpReconciliationRun runAfterGrace() {
        changedAt(clock.now().minusHours(3));
        return hpReconciliationUseCase.runScheduled();
    }

    private List<HpReconciliationMismatch> mismatchesOf(HpReconciliationRun run) {
        return mismatchRepository.findByRunIdOrderByIdAsc(run.getId());
    }

    private List<HpReconciliationRule> rulesOf(HpReconciliationRun run) {
        return mismatchesOf(run).stream().map(HpReconciliationMismatch::getRule).toList();
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("기록이 맞으면 어긋남이 없다")
    class Consistent {

        @Test
        @DisplayName("요청서: 요청 중(HELD)·거절(RELEASED)·확인(PAID + 지급 건)·일방 취소(MIXED + 위약금)·불참(RELEASED) → 5건 비교, 어긋남 0, 지표 0")
        void requests() {
            AppearanceRequestResDto requested = sendRequest(GUEST);
            AppearanceRequestResDto rejected = sendRequest(GUEST2);
            appearanceRequestFacade.reject(GUEST2, rejected.id(), "일정이 맞지 않습니다.");
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(4L));
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(5L));
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(6L));
            AppearanceRequestResDto toConfirm = appearanceRequestFacade.create(HOST, 4L,
                    AppearanceRequestFixture.content(clock.now()));
            AppearanceRequestResDto toCancel = appearanceRequestFacade.create(HOST, 5L,
                    AppearanceRequestFixture.content(clock.now()));
            AppearanceRequestResDto toAbsent = appearanceRequestFacade.create(HOST, 6L,
                    AppearanceRequestFixture.content(clock.now()));
            Long confirmed = acceptRequest(toConfirm);
            Long canceled = acceptRequest(toCancel);
            Long absent = acceptRequest(toAbsent);
            appearanceFacade.hostCancel(HOST, canceled, false, null);
            passBroadcast();
            appearanceFacade.confirm(HOST, confirmed);
            appearanceFacade.markAbsent(HOST, absent, null);
            assertNoPendingTask();

            walletRequest(requested.id(), hold(BillingHoldStatus.HELD, HOST, FEE, FEE, 0, 0), null);
            walletRequest(rejected.id(), hold(BillingHoldStatus.RELEASED, HOST, FEE, 0, FEE, 0), null);
            walletRequest(toConfirm.id(), hold(BillingHoldStatus.PAID, HOST, FEE, 0, 0, FEE), null,
                    settlement(SettlementSourceType.APPEARANCE_REQUEST, confirmed, PayoutType.NORMAL, 4L, FEE));
            walletRequest(toCancel.id(), hold(BillingHoldStatus.MIXED, HOST, FEE, 0, FEE - PENALTY, PENALTY), null,
                    settlement(SettlementSourceType.APPEARANCE_REQUEST, canceled, PayoutType.PENALTY, 5L, PENALTY));
            walletRequest(toAbsent.id(), hold(BillingHoldStatus.RELEASED, HOST, FEE, 0, FEE, 0), null);

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.COMPLETED);
            assertThat(run.getTriggerType()).isEqualTo(HpReconciliationTrigger.SCHEDULED);
            assertThat(run.getCheckedCount()).isEqualTo(5);
            assertThat(rulesOf(run)).isEmpty();
            assertThat(run.getMismatchCount()).isZero();
            for (HpReconciliationRule rule : HpReconciliationRule.values()) {
                assertThat(hpReconciliationMetrics.mismatches(rule)).as(rule.name()).isZero();
            }
            assertThat(hpReconciliationMetrics.lastCompletedEpochSeconds())
                    .isEqualTo(run.getFinishedAt().atZone(AppearanceTime.ZONE).toEpochSecond());
        }

        @Test
        @DisplayName("모집글: 3명 중 2명 선발 → 조기 마감(빈자리 1명 반환) → 1명 확인·1명 일방 취소 → 어긋남 0")
        void closedRecruit() {
            Long recruitId = postRecruit();
            Long confirmed = establishFromRecruit(recruitId, GUEST);
            Long canceled = establishFromRecruit(recruitId, GUEST2);
            appearanceRecruitFacade.closeEarly(HOST, recruitId);
            appearanceFacade.hostCancel(HOST, canceled, false, null);
            passBroadcast();
            appearanceFacade.confirm(HOST, confirmed);
            assertNoPendingTask();

            long total = AppearanceRecruitFixture.TOTAL_FEE;
            walletRecruit(recruitId, hold(BillingHoldStatus.MIXED, HOST, total, 0, FEE + (FEE - PENALTY), FEE + PENALTY),
                    settlement(SettlementSourceType.RECRUIT_APPEARANCE, confirmed, PayoutType.NORMAL, GUEST, FEE),
                    settlement(SettlementSourceType.RECRUIT_APPEARANCE, canceled, PayoutType.PENALTY, GUEST2, PENALTY));

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getCheckedCount()).isEqualTo(1);
            assertThat(rulesOf(run)).isEmpty();
        }

        @Test
        @DisplayName("모집 중 합의 취소는 자리가 다시 열려 반환하지 않는다: 보류 전부가 남아 있으면(HELD 90,000) 어긋남 0")
        void agreedCancelWhileRecruiting() {
            Long recruitId = postRecruit();
            Long appearanceId = establishFromRecruit(recruitId, GUEST);
            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");
            appearanceFacade.acceptCancel(HOST, appearanceId);
            assertThat(hpTaskRepository.findAll()).isEmpty();

            long total = AppearanceRecruitFixture.TOTAL_FEE;
            walletRecruit(recruitId, hold(BillingHoldStatus.HELD, HOST, total, total, 0, 0));

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getCheckedCount()).isEqualTo(1);
            assertThat(rulesOf(run)).isEmpty();
        }

        @Test
        @DisplayName("보류 실패 요청서: 보류 없음 / 무효화로 반환됨(RELEASED, 남은 0) → 어긋남 0")
        void holdFailed() {
            BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);
            assertThatThrownBy(() -> sendRequest(GUEST)).isInstanceOf(AppearanceException.class);
            assertThatThrownBy(() -> sendRequest(GUEST2)).isInstanceOf(AppearanceException.class);
            walletAvailableAgain();
            List<Long> ids = jdbcTemplate.queryForList(
                    "select id from appearances.requests where status = 'HOLD_FAILED' order by id", Long.class);
            assertThat(ids).hasSize(2);
            walletRequest(ids.get(1), hold(BillingHoldStatus.RELEASED, HOST, FEE, 0, FEE, 0),
                    new HoldStateDto.Voided(HoldVoidOutcome.RELEASED, FEE));

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getCheckedCount()).isEqualTo(2);
            assertThat(rulesOf(run)).isEmpty();
        }
    }

    @Nested
    @DisplayName("일부러 어긋나게 만든 기록을 규칙마다 잡는다 (이슈 완료 조건)")
    class Mismatches {

        @Test
        @DisplayName("거절됐는데 지갑 보류가 HELD로 남음 → HOLD_BALANCE_MISMATCH (반환 30,000이어야 함)")
        void rejectedButHeld(CapturedOutput output) {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");
            walletRequest(sent.id(), hold(BillingHoldStatus.HELD, HOST, FEE, FEE, 0, 0), null);

            HpReconciliationRun run = runAfterGrace();

            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.HOLD_BALANCE_MISMATCH);
            HpReconciliationMismatch mismatch = mismatchesOf(run).getFirst();
            assertThat(mismatch.getReferenceType()).isEqualTo(HpTaskRefType.APPEARANCE_REQUEST);
            assertThat(mismatch.getReferenceId()).isEqualTo(sent.id());
            assertThat(mismatch.getAppearanceState()).contains("REJECTED", "작업 반환 30000");
            assertThat(mismatch.getWalletState()).contains("보류 HELD", "남은 30000");
            assertThat(run.getMismatchCount()).isEqualTo(1);
            assertThat(hpReconciliationMetrics.mismatches(HpReconciliationRule.HOLD_BALANCE_MISMATCH)).isEqualTo(1);
            assertThat(output).contains(HpReconciliationUseCase.MISMATCH_LOG_TAG + " rule=HOLD_BALANCE_MISMATCH");
        }

        @Test
        @DisplayName("출연 확인인데 지갑에 지급 건 없음 → SETTLEMENT_MISSING (출연 ID를 남김)")
        void confirmedWithoutSettlement() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            Long appearanceId = acceptRequest(sent);
            passBroadcast();
            appearanceFacade.confirm(HOST, appearanceId);
            walletRequest(sent.id(), hold(BillingHoldStatus.PAID, HOST, FEE, 0, 0, FEE), null);

            HpReconciliationRun run = runAfterGrace();

            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.SETTLEMENT_MISSING);
            assertThat(mismatchesOf(run).getFirst().getAppearanceId()).isEqualTo(appearanceId);
        }

        @Test
        @DisplayName("호스트 일방 취소인데 위약금 지급 건 없음 → SETTLEMENT_MISSING")
        void hostCanceledWithoutPenalty() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            Long appearanceId = acceptRequest(sent);
            appearanceFacade.hostCancel(HOST, appearanceId, false, null);
            walletRequest(sent.id(), hold(BillingHoldStatus.MIXED, HOST, FEE, 0, FEE - PENALTY, PENALTY), null);

            HpReconciliationRun run = runAfterGrace();

            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.SETTLEMENT_MISSING);
            assertThat(mismatchesOf(run).getFirst().getAppearanceState()).contains("PENALTY");
        }

        @Test
        @DisplayName("모집 마감 뒤 남은 보류(60,000) ≠ 남은 출연료 합(30,000, 빈자리 반환 누락) → HOLD_BALANCE_MISMATCH")
        void closedRecruitRemainingDiffers() {
            Long recruitId = postRecruit();
            establishFromRecruit(recruitId, GUEST);
            appearanceRecruitFacade.closeEarly(HOST, recruitId);
            long total = AppearanceRecruitFixture.TOTAL_FEE;
            walletRecruit(recruitId, hold(BillingHoldStatus.HELD, HOST, total, total - FEE, FEE, 0));

            HpReconciliationRun run = runAfterGrace();

            // 출연 쪽 반환 작업은 빈자리 2명분(60,000)이다
            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.HOLD_BALANCE_MISMATCH);
        }

        @Test
        @DisplayName("출연 쪽 누락: 모집 마감 뒤 빈자리 반환 작업이 없음 → HP_TASK_MISMATCH (+ 지갑은 반환했으므로 HOLD_BALANCE_MISMATCH)")
        void closedRecruitTaskMissing() {
            Long recruitId = postRecruit();
            establishFromRecruit(recruitId, GUEST);
            appearanceRecruitFacade.closeEarly(HOST, recruitId);
            jdbcTemplate.update("delete from appearances.hp_tasks where ref_id = ?", recruitId);
            long total = AppearanceRecruitFixture.TOTAL_FEE;
            walletRecruit(recruitId, hold(BillingHoldStatus.PARTIALLY_PROCESSED, HOST, total, FEE, total - FEE, 0));

            HpReconciliationRun run = runAfterGrace();

            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.HP_TASK_MISMATCH,
                    HpReconciliationRule.HOLD_BALANCE_MISMATCH);
            assertThat(mismatchesOf(run).getFirst().getAppearanceState()).contains("기대 남은 보류 30000", "작업 반환 0");
        }

        @Test
        @DisplayName("요청 중(REQUESTED)인데 지갑 보류가 이미 반환·무효화됨 → VOIDED_WHILE_ACTIVE + HOLD_BALANCE_MISMATCH")
        void requestedButReleased() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            walletRequest(sent.id(), hold(BillingHoldStatus.RELEASED, HOST, FEE, 0, FEE, 0),
                    new HoldStateDto.Voided(HoldVoidOutcome.RELEASED, FEE));

            HpReconciliationRun run = runAfterGrace();

            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.VOIDED_WHILE_ACTIVE,
                    HpReconciliationRule.HOLD_BALANCE_MISMATCH);
        }

        @Test
        @DisplayName("지갑에만 있는 보류: 지갑 목록의 요청서 ID가 출연에 없음 → WALLET_ONLY_HOLD")
        void walletOnly() {
            HoldStateDto orphan = new HoldStateDto(BillingReferenceType.APPEARANCE_REQUEST, 999_999L,
                    hold(BillingHoldStatus.HELD, HOST, FEE, FEE, 0, 0), null, List.of());
            listed.put(BillingReferenceType.APPEARANCE_REQUEST, List.of(orphan));

            HpReconciliationRun run = runAfterGrace();

            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.WALLET_ONLY_HOLD);
            HpReconciliationMismatch mismatch = mismatchesOf(run).getFirst();
            assertThat(mismatch.getReferenceId()).isEqualTo(999_999L);
            assertThat(mismatch.getWalletState()).contains("보류 HELD");
            // 출연에 없는 ID는 지갑 보류 조회로 다시 묻지 않는다
            verify(billingClient, never()).queryHolds(any());
        }

        @Test
        @DisplayName("보류가 있어야 하는데 지갑에 없음 → HOLD_MISSING / 최초 보류·보류한 회원이 다름 → HOLD_MISMATCH")
        void holdMissingOrDifferent() {
            AppearanceRequestResDto missing = sendRequest(GUEST);
            AppearanceRequestResDto different = sendRequest(GUEST2);
            walletRequest(different.id(), hold(BillingHoldStatus.HELD, GUEST, FEE, FEE, 0, 0), null);

            HpReconciliationRun run = runAfterGrace();

            assertThat(mismatchesOf(run)).extracting(HpReconciliationMismatch::getReferenceId,
                            HpReconciliationMismatch::getRule)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(missing.id(), HpReconciliationRule.HOLD_MISSING),
                            org.assertj.core.groups.Tuple.tuple(different.id(), HpReconciliationRule.HOLD_MISMATCH));
        }

        @Test
        @DisplayName("지급 건의 받는 회원이 다름 → SETTLEMENT_MISMATCH / 작업에 없는 지급 건 → SETTLEMENT_UNEXPECTED")
        void settlementDiffers() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            Long appearanceId = acceptRequest(sent);
            passBroadcast();
            appearanceFacade.confirm(HOST, appearanceId);
            walletRequest(sent.id(), hold(BillingHoldStatus.PAID, HOST, FEE, 0, 0, FEE), null,
                    settlement(SettlementSourceType.APPEARANCE_REQUEST, appearanceId, PayoutType.NORMAL, GUEST2, FEE),
                    settlement(SettlementSourceType.APPEARANCE_REQUEST, appearanceId, PayoutType.PENALTY, GUEST, 1));

            HpReconciliationRun run = runAfterGrace();

            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.SETTLEMENT_MISMATCH,
                    HpReconciliationRule.SETTLEMENT_UNEXPECTED);
        }

        @Test
        @DisplayName("보류 실패로 끝났는데 지갑 보류가 남음 → HOLD_NOT_CLOSED")
        void holdFailedButHeld() {
            BillingStubs.reject(billingClient, Rejection.INSUFFICIENT_BALANCE);
            assertThatThrownBy(() -> sendRequest(GUEST)).isInstanceOf(AppearanceException.class);
            walletAvailableAgain();
            Long id = jdbcTemplate.queryForObject("select id from appearances.requests", Long.class);
            walletRequest(id, hold(BillingHoldStatus.HELD, HOST, FEE, FEE, 0, 0), null);

            HpReconciliationRun run = runAfterGrace();

            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.HOLD_NOT_CLOSED);
        }
    }

    @Nested
    @DisplayName("진행 중인 건은 비교하지 않는다")
    class InProgress {

        @Test
        @DisplayName("유예 시간(2시간) 안에 바뀐 건: 지갑이 어긋나 있어도 비교하지 않고, 지갑에 묻지도 않는다")
        void withinSettleGrace() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");
            walletRequest(sent.id(), hold(BillingHoldStatus.HELD, HOST, FEE, FEE, 0, 0), null);

            HpReconciliationRun run = hpReconciliationUseCase.runScheduled();

            assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.COMPLETED);
            assertThat(run.getCheckedCount()).isZero();
            assertThat(rulesOf(run)).isEmpty();
            verify(billingClient, never()).queryHolds(any());
        }

        @Test
        @DisplayName("보류 결과 대기(HOLD_PENDING)와 재시도를 기다리는 HP 작업(PENDING)이 있는 건은 비교하지 않는다")
        void holdPendingOrPendingTask() {
            BillingStubs.unavailable(billingClient);
            assertThatThrownBy(() -> sendRequest(GUEST)).isInstanceOf(AppearanceException.class);
            walletAvailableAgain();
            AppearanceRequestResDto rejected = sendRequest(GUEST2);
            BillingStubs.unavailable(billingClient);
            appearanceRequestFacade.reject(GUEST2, rejected.id(), "일정이 맞지 않습니다.");
            walletAvailableAgain();
            assertThat(hpTaskRepository.findAll()).extracting(HpTask::getStatus).containsExactly(HpTaskStatus.PENDING);

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getCheckedCount()).isZero();
            verify(billingClient, never()).queryHolds(any());
        }

        @Test
        @DisplayName("비교 범위(7일) 밖에서 바뀐 건은 출연 쪽 대상에서 빠진다 (지갑 목록에 오면 다시 본다)")
        void outsideLookback() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");
            changedAt(clock.now().minusDays(7).minusMinutes(1));
            walletRequest(sent.id(), hold(BillingHoldStatus.HELD, HOST, FEE, FEE, 0, 0), null);

            HpReconciliationRun notInRange = hpReconciliationUseCase.runScheduled();
            assertThat(notInRange.getCheckedCount()).isZero();

            listed.put(BillingReferenceType.APPEARANCE_REQUEST,
                    List.of(wallet.get(key(BillingReferenceType.APPEARANCE_REQUEST, sent.id()))));
            HpReconciliationRun fromWallet = hpReconciliationUseCase.runScheduled();
            assertThat(fromWallet.getCheckedCount()).isEqualTo(1);
            assertThat(rulesOf(fromWallet)).containsExactly(HpReconciliationRule.HOLD_BALANCE_MISMATCH);
        }

        @Test
        @DisplayName("격리된(DEAD) HP 작업이 있는 건은 비교하지 않고 HP_TASK_DEAD로 표시만 한다 (어긋난 건 수·지표의 어긋남에는 세지 않음)")
        void deadTask() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");
            jdbcTemplate.update("update appearances.hp_tasks set status = 'DEAD'");

            HpReconciliationRun run = runAfterGrace();

            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.HP_TASK_DEAD);
            assertThat(run.getCheckedCount()).isEqualTo(1);
            assertThat(run.getDeadCount()).isEqualTo(1);
            assertThat(run.getMismatchCount()).isZero();
            assertThat(mismatchesOf(run).getFirst().getAppearanceState()).contains("DEAD [RELEASE#");
            assertThat(hpReconciliationMetrics.mismatches(HpReconciliationRule.HP_TASK_DEAD)).isEqualTo(1);
            verify(billingClient, never()).queryHolds(any());
        }
    }

    @Nested
    @DisplayName("비교하는 사이에 바뀐 건")
    class ChangedMeanwhile {

        @Test
        @DisplayName("출연을 읽은 뒤 지갑 조회 전에 작업이 바뀌면(다른 배치 실행) 어긋남을 남기지 않고 비교 수에도 넣지 않는다. 다음 실행이 다시 본다")
        void skipsAndRechecksNextRun() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");
            walletRequest(sent.id(), hold(BillingHoldStatus.HELD, HOST, FEE, FEE, 0, 0), null);
            HoldStateDto held = wallet.get(key(BillingReferenceType.APPEARANCE_REQUEST, sent.id()));
            doAnswer(invocation -> {
                // 지갑을 조회하는 동안 다른 배치가 이 요청서의 작업을 바꾼다
                jdbcTemplate.update("update appearances.hp_tasks set updated_at = ?", clock.now().minusHours(2).minusMinutes(30));
                return new BillingCallResult.Succeeded<>(List.of(held));
            }).when(billingClient).queryHolds(any());

            HpReconciliationRun changed = runAfterGrace();

            assertThat(changed.getStatus()).isEqualTo(HpReconciliationStatus.COMPLETED);
            assertThat(changed.getCheckedCount()).isZero();
            assertThat(rulesOf(changed)).isEmpty();

            stubWallet();
            HpReconciliationRun next = runAfterGrace();
            assertThat(next.getCheckedCount()).isEqualTo(1);
            assertThat(rulesOf(next)).containsExactly(HpReconciliationRule.HOLD_BALANCE_MISMATCH);
        }

        @Test
        @DisplayName("어긋남이 없으면 다시 읽지 않는다 (출연 쪽 조회는 대상 모음 1번뿐)")
        void noRecheckWhenConsistent() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");
            walletRequest(sent.id(), hold(BillingHoldStatus.RELEASED, HOST, FEE, 0, FEE, 0), null);
            doAnswer(invocation -> {
                // 바뀌어도 어긋남이 없으면 다시 읽지 않으므로 그대로 비교 수에 들어간다
                jdbcTemplate.update("update appearances.hp_tasks set updated_at = ?", clock.now().minusHours(2).minusMinutes(30));
                HoldQueryCommand command = invocation.getArgument(0);
                return new BillingCallResult.Succeeded<>(List.of(
                        wallet.get(key(command.referenceType(), command.referenceIds().getFirst()))));
            }).when(billingClient).queryHolds(any());

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getCheckedCount()).isEqualTo(1);
            assertThat(rulesOf(run)).isEmpty();
        }
    }

    @Nested
    @DisplayName("지갑 조회가 실패하면 그 실행을 미완료로 끝낸다")
    class WalletFailure {

        @Test
        @DisplayName("지갑 보류 조회 결과를 받지 못함 → INCOMPLETE, 커서를 저장하지 않고 지표는 바꾸지 않는다. 다음 실행에서 다시 본다")
        void queryUnavailable() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");
            walletRequest(sent.id(), hold(BillingHoldStatus.HELD, HOST, FEE, FEE, 0, 0), null);
            doThrow(BillingCallExceptions.unknownResult("timeout")).when(billingClient).queryHolds(any());
            // 지표는 앱 전체에 하나라 앞 테스트의 값이 남아 있을 수 있다. 바뀌지 않는지만 본다
            long before = hpReconciliationMetrics.mismatches(HpReconciliationRule.HOLD_BALANCE_MISMATCH);

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.INCOMPLETE);
            assertThat(run.getFailureReason()).contains("지갑 보류 조회", "결과를 받지 못함");
            assertThat(run.getFinishedAt()).isNotNull();
            assertThat(cursorRepository.findAll()).isEmpty();
            assertThat(hpReconciliationMetrics.mismatches(HpReconciliationRule.HOLD_BALANCE_MISMATCH))
                    .isEqualTo(before);
            assertThat(hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE)).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("지갑 보류 목록이 거절됨 → INCOMPLETE (이유에 거절 코드)")
        void listRejected() {
            doReturn(Rejection.WALLET_NOT_FOUND.failed()).when(billingClient)
                    .listHolds(any(), any(), anyLong(), anyInt());

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.INCOMPLETE);
            assertThat(run.getFailureReason()).contains("지갑 보류 목록", "WALLET_NOT_FOUND");
        }

        @Test
        @DisplayName("지갑 회로가 열려 있으면 지갑을 부르지 않고 INCOMPLETE")
        void circuitOpen() {
            doReturn(true).when(billingClient).isCircuitOpen();

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.INCOMPLETE);
            assertThat(run.getFailureReason()).isEqualTo("지갑 회로가 열려 있음");
            verify(billingClient, never()).listHolds(any(), any(), anyLong(), anyInt());
            verify(billingClient, never()).queryHolds(any());
        }

        @Test
        @DisplayName("미완료여도 그때까지 찾은 어긋남은 남는다 (요청서는 비교, 모집글 목록에서 실패)")
        void keepsFindingsBeforeFailure() {
            AppearanceRequestResDto sent = sendRequest(GUEST);
            appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");
            walletRequest(sent.id(), hold(BillingHoldStatus.HELD, HOST, FEE, FEE, 0, 0), null);
            doThrow(BillingCallExceptions.unknownResult("timeout")).when(billingClient)
                    .listHolds(eq(BillingReferenceType.APPEARANCE_RECRUIT), any(), anyLong(), anyInt());

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.INCOMPLETE);
            assertThat(run.getCheckedCount()).isEqualTo(1);
            assertThat(run.getMismatchCount()).isEqualTo(1);
            assertThat(rulesOf(run)).containsExactly(HpReconciliationRule.HOLD_BALANCE_MISMATCH);
        }
    }

    @Nested
    @DisplayName("지갑 보류 목록 커서")
    class Cursor {

        @Test
        @DisplayName("hasMore 동안 응답 커서로 이어 읽고, 완료되면 마지막 커서를 KST로 저장해 다음 실행이 거기서 시작한다")
        void pagesAndResumes() {
            Instant first = Instant.parse("2026-11-03T01:00:00Z");
            Instant last = Instant.parse("2026-11-03T02:30:00.123456Z");
            doReturn(new BillingCallResult.Succeeded<>(new HoldStatePageDto(List.of(), first, 10L, true)))
                    .when(billingClient).listHolds(BillingReferenceType.APPEARANCE_REQUEST, Instant.EPOCH, 0L, 500);
            doReturn(new BillingCallResult.Succeeded<>(new HoldStatePageDto(List.of(), last, 20L, false)))
                    .when(billingClient).listHolds(BillingReferenceType.APPEARANCE_REQUEST, first, 10L, 500);

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.COMPLETED);
            assertThat(cursorRepository.findById(HpTaskRefType.APPEARANCE_REQUEST)).hasValueSatisfying(cursor -> {
                assertThat(cursor.getUpdatedAfter()).isEqualTo(LocalDateTime.ofInstant(last, AppearanceTime.ZONE));
                assertThat(cursor.getAfterId()).isEqualTo(20L);
            });
            assertThat(cursorRepository.findById(HpTaskRefType.APPEARANCE_RECRUIT)).hasValueSatisfying(cursor ->
                    assertThat(cursor.getAfterId()).isZero());

            hpReconciliationUseCase.runScheduled();

            verify(billingClient).listHolds(BillingReferenceType.APPEARANCE_REQUEST, last, 20L, 500);
        }
    }

    @Nested
    @DisplayName("실행 관리")
    class Running {

        @Test
        @DisplayName("관리자 실행 중이면 다른 관리자 실행은 409 HP_RECONCILIATION_RUNNING, 배치는 건너뛴다(null). 끝나면 다시 실행할 수 있다")
        void onlyOneAtATime() throws Exception {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.user(ADMIN,
                    com.hapbang.shared.member.domain.Role.ADMIN, com.hapbang.shared.member.domain.UserStatus.ACTIVE));
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                        entered.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                        return new BillingCallResult.Succeeded<>(
                                new HoldStatePageDto(List.of(), Instant.EPOCH, 0, false));
                    }).when(billingClient).listHolds(eq(BillingReferenceType.APPEARANCE_REQUEST), any(), anyLong(),
                    anyInt());
            CompletableFuture<HpReconciliationRun> first =
                    CompletableFuture.supplyAsync(() -> hpReconciliationUseCase.runManual(ADMIN));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> hpReconciliationUseCase.runManual(ADMIN))
                    .isInstanceOfSatisfying(AppearanceException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.HP_RECONCILIATION_RUNNING));
            assertThat(hpReconciliationUseCase.runScheduled()).isNull();
            assertThat(runRepository.findAll()).extracting(HpReconciliationRun::getStatus)
                    .containsExactly(HpReconciliationStatus.RUNNING);

            release.countDown();
            HpReconciliationRun finished = first.get(10, TimeUnit.SECONDS);
            assertThat(finished.getStatus()).isEqualTo(HpReconciliationStatus.COMPLETED);
            assertThat(finished.getTriggerType()).isEqualTo(HpReconciliationTrigger.MANUAL);
            assertThat(finished.getTriggeredBy()).isEqualTo(ADMIN);
            assertThat(hpReconciliationUseCase.runScheduled()).isNotNull();
        }

        @Test
        @DisplayName("abandon-after(1시간)보다 오래 RUNNING으로 남은 실행은 다음 실행이 시작될 때 INCOMPLETE(앱이 멈춰 끝나지 못한 실행)로 정리하고 중단 횟수를 올린다 (#821)")
        void abandonedRunIsClosed() {
            runningSince(clock.now().minusHours(1).minusMinutes(1));
            double incompleteBefore = hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE);

            HpReconciliationRun run = runAfterGrace();

            assertThat(runRepository.findAll()).extracting(HpReconciliationRun::getStatus, HpReconciliationRun::getFailureReason)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(HpReconciliationStatus.INCOMPLETE,
                                    HpReconciliationSupport.ABANDONED_REASON),
                            org.assertj.core.groups.Tuple.tuple(HpReconciliationStatus.COMPLETED, null));
            assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.COMPLETED);
            assertThat(hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE)).isEqualTo(incompleteBefore + 1);
        }

        @Test
        @DisplayName("abandon-after 안에 시작한 RUNNING 실행은 배포 중 옛 pod가 하고 있는 것일 수 있어 정리하지 않는다 (#821)")
        void recentRunningRunIsKept() {
            Long otherPodRun = runningSince(clock.now().minusMinutes(59));
            double incompleteBefore = hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE);

            HpReconciliationRun run = runAfterGrace();

            assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.COMPLETED);
            assertThat(runRepository.findById(otherPodRun).orElseThrow().getStatus())
                    .isEqualTo(HpReconciliationStatus.RUNNING);
            assertThat(hpReconciliationMetrics.runs(HpReconciliationStatus.INCOMPLETE)).isEqualTo(incompleteBefore);
        }

        /** 다른 pod가 시작해 RUNNING으로 남은 실행을 DB에 바로 넣는다 */
        private Long runningSince(LocalDateTime startedAt) {
            return runRepository.save(HpReconciliationRun.start(HpReconciliationTrigger.SCHEDULED, null, startedAt))
                    .getId();
        }
    }

    @Nested
    @DisplayName("기동할 때 지표 복원 (#821)")
    class Restore {

        @Test
        @DisplayName("마지막 COMPLETED 실행의 규칙별 건수(HP_TASK_DEAD 포함)와 끝난 시각으로 채운다. 그 뒤의 INCOMPLETE 실행은 보지 않는다")
        void fromLatestCompletedRun() {
            LocalDateTime finishedAt = clock.now().withNano(0).minusHours(5);
            savedRun(HpReconciliationStatus.COMPLETED, finishedAt.minusDays(1), HpReconciliationRule.HOLD_MISSING);
            savedRun(HpReconciliationStatus.COMPLETED, finishedAt, HpReconciliationRule.HOLD_BALANCE_MISMATCH,
                    HpReconciliationRule.HOLD_BALANCE_MISMATCH, HpReconciliationRule.HP_TASK_DEAD);
            savedRun(HpReconciliationStatus.INCOMPLETE, finishedAt.plusHours(1), HpReconciliationRule.SETTLEMENT_MISSING);
            hpReconciliationMetrics.updateMismatches(Map.of());
            hpReconciliationMetrics.updateLastCompleted(finishedAt.minusDays(30));

            hpReconciliationMetricsRestorer.restore();

            assertThat(hpReconciliationMetrics.mismatches(HpReconciliationRule.HOLD_BALANCE_MISMATCH)).isEqualTo(2);
            assertThat(hpReconciliationMetrics.mismatches(HpReconciliationRule.HP_TASK_DEAD)).isEqualTo(1);
            assertThat(hpReconciliationMetrics.mismatches(HpReconciliationRule.HOLD_MISSING)).isZero();
            assertThat(hpReconciliationMetrics.mismatches(HpReconciliationRule.SETTLEMENT_MISSING)).isZero();
            assertThat(hpReconciliationMetrics.lastCompletedEpochSeconds())
                    .isEqualTo(finishedAt.atZone(AppearanceTime.ZONE).toEpochSecond());
        }

        @Test
        @DisplayName("COMPLETED 실행이 없으면 지표를 바꾸지 않고, RUNNING 실행도 정리하지 않는다(DB를 읽기만 한다)")
        void noCompletedRun() {
            LocalDateTime finishedAt = clock.now().withNano(0).minusHours(5);
            savedRun(HpReconciliationStatus.INCOMPLETE, finishedAt, HpReconciliationRule.HOLD_MISSING);
            Long running = runRepository.save(HpReconciliationRun.start(HpReconciliationTrigger.SCHEDULED, null,
                    finishedAt.minusDays(1))).getId();
            hpReconciliationMetrics.updateMismatches(Map.of());
            hpReconciliationMetrics.updateLastCompleted(finishedAt.minusDays(30));

            hpReconciliationMetricsRestorer.restore();

            assertThat(hpReconciliationMetrics.mismatches(HpReconciliationRule.HOLD_MISSING)).isZero();
            assertThat(hpReconciliationMetrics.lastCompletedEpochSeconds())
                    .isEqualTo(finishedAt.minusDays(30).atZone(AppearanceTime.ZONE).toEpochSecond());
            assertThat(runRepository.findById(running).orElseThrow().getStatus())
                    .isEqualTo(HpReconciliationStatus.RUNNING);
        }

        /** 끝난 실행과 그 실행의 어긋난 건(규칙마다 한 건)을 DB에 바로 남긴다 */
        private void savedRun(HpReconciliationStatus status, LocalDateTime finishedAt, HpReconciliationRule... rules) {
            HpReconciliationRun run = HpReconciliationRun.start(HpReconciliationTrigger.SCHEDULED, null,
                    finishedAt.minusMinutes(1));
            int dead = (int) Arrays.stream(rules).filter(rule -> rule == HpReconciliationRule.HP_TASK_DEAD).count();
            if (status == HpReconciliationStatus.COMPLETED) {
                run.complete(rules.length, rules.length - dead, dead, finishedAt);
            } else {
                run.incomplete(rules.length, rules.length - dead, dead, "시험용 중단", finishedAt);
            }
            Long runId = runRepository.save(run).getId();
            long refId = 1;
            for (HpReconciliationRule rule : rules) {
                mismatchRepository.save(new HpReconciliationMismatch(runId, HpTaskRefType.APPEARANCE_REQUEST, refId++,
                        null, rule, "시험 출연 상태", "시험 지갑 상태"));
            }
        }
    }
}
