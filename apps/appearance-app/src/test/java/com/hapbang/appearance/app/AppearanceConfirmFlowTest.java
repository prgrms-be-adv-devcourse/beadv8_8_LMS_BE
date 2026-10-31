package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.domain.AppearanceConfirmType;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingCalls;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.shared.billing.dto.PayoutCommand;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 출연 확인·불참·자동 확인을 DB와 Billing 대역으로 확인한다 (ISSUE.md APR-35).
 * 출연료 30,000 HP. 확인 → 게스트에게 30,000 지급, 불참 → 호스트에게 30,000 반환. Billing은 대역이라 보낸 지급·반환 요청 내용을 본다
 * (실제 잔액 변화는 apps 통합 테스트).
 * 시각은 실제 순서대로 테스트 시계를 앞으로 돌려 만든다 (모집 마감 → 방송 → 확인 기간 끝, #297).
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceConfirmFlowTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long OTHER = 4L;
    private static final long FEE = 30_000L;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceAutoConfirmUseCase appearanceAutoConfirmUseCase;

    @Autowired
    AppearanceRecruitDeadlineCloseUseCase appearanceRecruitDeadlineCloseUseCase;

    @Autowired
    AppearanceCancelAutoRejectUseCase appearanceCancelAutoRejectUseCase;

    @Autowired
    HpTaskRetryUseCase hpTaskRetryUseCase;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

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
        for (Long id : List.of(HOST, GUEST, GUEST2, OTHER)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    private LocalDateTime now() {
        return clock.now();
    }

    /** 요청서가 수락돼 성립한 출연 */
    private RequestAppearance establishFromRequest() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(now()));
        return new RequestAppearance(sent.id(),
                appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId());
    }

    private Long postRecruit(int recruitCount) {
        return appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(now(), FEE, recruitCount)).id();
    }

    /** 게스트가 지원하고 호스트가 수락해 성립한 출연 ID */
    private Long establishFromRecruit(Long recruitId, Long guestId) {
        Long applicationId = appearanceApplicationFacade.apply(guestId, recruitId, "지원합니다.").id();
        return appearanceApplicationFacade.accept(HOST, applicationId).appearanceId();
    }

    private LocalDateTime scheduledAtOf(Long appearanceId) {
        return jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId);
    }

    /** 모집 마감 일시가 지나 기한 마감 배치가 돈다 (방송 전에 일어나는 일) */
    private void recruitDeadlinePassed(Long recruitId) {
        clock.advanceTo(jdbcTemplate.queryForObject("select recruit_deadline from appearances.recruits where id = ?",
                LocalDateTime.class, recruitId));
        appearanceRecruitDeadlineCloseUseCase.closeDue();
    }

    /** 방송이 끝나 확인 기간에 들어선다 (시계를 방송 예정 일시 1시간 뒤로) */
    private void broadcastEnded(Long appearanceId) {
        clock.advanceTo(scheduledAtOf(appearanceId).plusHours(1));
    }

    /** 확인 기간(방송 예정 일시 + 7일)이 끝난다 */
    private void confirmPeriodEnded(Long appearanceId) {
        clock.advanceTo(scheduledAtOf(appearanceId).plusDays(7));
    }

    /** 실패한 HP 작업의 다음 시도 시각이 된다 */
    private void retryTimeReached(HpTask task) {
        clock.advanceTo(hpTaskRepository.findById(task.getId()).orElseThrow().getNextAttemptAt());
    }

    private List<HpTask> tasksOf(HpTaskType type) {
        List<HpTask> tasks = new ArrayList<>();
        for (HpTask task : hpTaskRepository.findAll()) {
            if (task.getTaskType() == type) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    private HpTask onlyTask(HpTaskType type) {
        List<HpTask> tasks = tasksOf(type);
        assertThat(tasks).hasSize(1);
        return tasks.getFirst();
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private record RequestAppearance(Long requestId, Long appearanceId) {
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 확인")
    class Confirm {

        @Test
        @DisplayName("요청서 출처: CONFIRMED(HOST), 지급 작업 PAYOUT·APPEARANCE_CONFIRMED·DONE, Billing에 게스트 지급 30,000(출연료 전부) 요청")
        void fromRequest() {
            RequestAppearance established = establishFromRequest();
            broadcastEnded(established.appearanceId());

            AppearanceResDto confirmed = appearanceFacade.confirm(HOST, established.appearanceId());

            assertThat(confirmed.status()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(confirmed.confirmType()).isEqualTo(AppearanceConfirmType.HOST);
            assertThat(confirmed.confirmedAt()).isNotNull();
            assertThat(confirmed.hpPayoutStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(confirmed.hpReleaseStatus()).isNull();
            HpTask task = onlyTask(HpTaskType.PAYOUT);
            assertThat(task.getReason()).isEqualTo(HpTaskReason.APPEARANCE_CONFIRMED);
            assertThat(task.getIdempotencyKey()).isEqualTo("APPEARANCE_REQUEST-%d-PAYOUT-APPEARANCE-%d"
                    .formatted(established.requestId(), established.appearanceId()));
            assertThat(task.getMemberId()).isEqualTo(HOST);
            assertThat(task.getReceiverId()).isEqualTo(GUEST);
            assertThat(task.getAmount()).isEqualTo(FEE);
            assertThat(task.getPayoutAmount()).isEqualTo(FEE);
            assertThat(task.getAppearanceId()).isEqualTo(established.appearanceId());
            assertThat(BillingCalls.payouts(billingClient)).singleElement().satisfies(payout -> {
                assertThat(payout.payerUserId()).isEqualTo(HOST);
                assertThat(payout.referenceType()).isEqualTo(BillingReferenceType.APPEARANCE_REQUEST);
                assertThat(payout.referenceId()).isEqualTo(established.requestId());
                assertThat(payout.payoutRequestId()).isEqualTo(task.getIdempotencyKey());
                assertThat(payout.receiverUserId()).isEqualTo(GUEST);
                assertThat(payout.sourceType()).isEqualTo(SettlementSourceType.APPEARANCE_REQUEST);
                assertThat(payout.sourceId()).isEqualTo(established.appearanceId());
                assertThat(payout.amountHp()).isEqualTo(FEE);
            });
        }

        @Test
        @DisplayName("모집 출처(정원 3, 성립 2): 마감 배치가 빈자리 30,000 반환 → 방송 뒤 한 출연 확인 → 게스트 지급 30,000, "
                + "다른 출연 불참 → 호스트 반환 30,000 (보류 90,000을 모두 정산하는 요청)")
        void fromRecruit() {
            Long recruitId = postRecruit(3);
            Long first = establishFromRecruit(recruitId, GUEST);
            Long second = establishFromRecruit(recruitId, GUEST2);
            recruitDeadlinePassed(recruitId);
            broadcastEnded(first);

            appearanceFacade.confirm(HOST, first);
            assertThat(BillingCalls.payouts(billingClient)).singleElement().satisfies(payout -> {
                assertThat(payout.referenceType()).isEqualTo(BillingReferenceType.APPEARANCE_RECRUIT);
                assertThat(payout.referenceId()).isEqualTo(recruitId);
                assertThat(payout.sourceType()).isEqualTo(SettlementSourceType.RECRUIT_APPEARANCE);
                assertThat(payout.sourceId()).isEqualTo(first);
                assertThat(payout.amountHp()).isEqualTo(FEE);
            });
            AppearanceResDto absent = appearanceFacade.markAbsent(HOST, second, null);

            assertThat(absent.status()).isEqualTo(AppearanceStatus.ABSENT);
            assertThat(absent.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(onlyTask(HpTaskType.PAYOUT).getIdempotencyKey())
                    .isEqualTo("APPEARANCE_RECRUIT-%d-PAYOUT-APPEARANCE-%d".formatted(recruitId, first));
            assertThat(tasksOf(HpTaskType.RELEASE)).extracting(HpTask::getReason, HpTask::getIdempotencyKey)
                    .containsExactlyInAnyOrder(
                            tuple(HpTaskReason.RECRUIT_CLOSED, "APPEARANCE_RECRUIT-%d-RELEASE-1".formatted(recruitId)),
                            tuple(HpTaskReason.APPEARANCE_ABSENT,
                                    "APPEARANCE_RECRUIT-%d-RELEASE-APPEARANCE-%d".formatted(recruitId, second)));
            assertThat(BillingCalls.releases(billingClient)).allSatisfy(release -> {
                assertThat(release.referenceId()).isEqualTo(recruitId);
                assertThat(release.amountHp()).isEqualTo(FEE);
            }).hasSize(2);
            assertThat(BillingCalls.payouts(billingClient)).extracting(PayoutCommand::receiverUserId)
                    .containsExactly(GUEST);
        }

        @Test
        @DisplayName("방송 예정 일시 전 APPEARANCE_CONFIRM_NOT_OPEN, 게스트 NOT_APPEARANCE_HOST, 다른 회원 APPEARANCE_NOT_FOUND, "
                + "확인 기간 끝 APPEARANCE_CONFIRM_PERIOD_ENDED. 어느 경우도 작업이 생기지 않고 Billing에 지급을 요청하지 않는다")
        void rejected() {
            Long appearanceId = establishFromRequest().appearanceId();

            assertErrorCode(() -> appearanceFacade.confirm(HOST, appearanceId),
                    AppearanceErrorCode.APPEARANCE_CONFIRM_NOT_OPEN);
            broadcastEnded(appearanceId);
            assertErrorCode(() -> appearanceFacade.confirm(GUEST, appearanceId),
                    AppearanceErrorCode.NOT_APPEARANCE_HOST);
            assertErrorCode(() -> appearanceFacade.markAbsent(OTHER, appearanceId, null),
                    AppearanceErrorCode.APPEARANCE_NOT_FOUND);
            confirmPeriodEnded(appearanceId);
            assertErrorCode(() -> appearanceFacade.confirm(HOST, appearanceId),
                    AppearanceErrorCode.APPEARANCE_CONFIRM_PERIOD_ENDED);

            assertThat(appearanceFacade.getDetail(HOST, appearanceId).status()).isEqualTo(AppearanceStatus.ESTABLISHED);
            assertThat(tasksOf(HpTaskType.PAYOUT)).isEmpty();
            assertThat(BillingCalls.payouts(billingClient)).isEmpty();
        }

        @Test
        @DisplayName("한 번 응답하면 번복할 수 없다: 확인 뒤 불참·다시 확인 모두 APPEARANCE_NOT_CONFIRMABLE, 지급은 한 번")
        void noChange() {
            Long appearanceId = establishFromRequest().appearanceId();
            broadcastEnded(appearanceId);
            appearanceFacade.confirm(HOST, appearanceId);

            assertErrorCode(() -> appearanceFacade.markAbsent(HOST, appearanceId, "불참"),
                    AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE);
            assertErrorCode(() -> appearanceFacade.confirm(HOST, appearanceId),
                    AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE);

            assertThat(tasksOf(HpTaskType.PAYOUT)).hasSize(1);
            assertThat(tasksOf(HpTaskType.RELEASE)).isEmpty();
            assertThat(BillingCalls.payouts(billingClient)).extracting(PayoutCommand::receiverUserId)
                    .containsExactly(GUEST);
        }

        @Test
        @DisplayName("방송 예정 일시가 지났는데 취소 요청이 처리 중이면 APPEARANCE_NOT_CONFIRMABLE → 자동 거절 배치 뒤에는 확인할 수 있다")
        void pendingCancelRequest() {
            Long appearanceId = establishFromRequest().appearanceId();
            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");
            broadcastEnded(appearanceId);

            assertErrorCode(() -> appearanceFacade.confirm(HOST, appearanceId),
                    AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE);

            appearanceCancelAutoRejectUseCase.rejectDue();
            assertThat(appearanceFacade.confirm(HOST, appearanceId).status()).isEqualTo(AppearanceStatus.CONFIRMED);
        }

        @Test
        @DisplayName("정지된 호스트도 확인할 수 있고(응답은 출연 상세), 정지된 게스트에게도 지급된다. 상세 조회는 정지 회원이면 막힌다")
        void suspendedMembers() {
            Long appearanceId = establishFromRequest().appearanceId();
            broadcastEnded(appearanceId);
            appearanceUserFacade.syncModified(AppearanceUserFixture.user(HOST, Role.INFLUENCER, UserStatus.SUSPENDED));
            appearanceUserFacade.syncModified(AppearanceUserFixture.user(GUEST, Role.INFLUENCER, UserStatus.SUSPENDED));

            AppearanceResDto confirmed = appearanceFacade.confirm(HOST, appearanceId);

            assertThat(confirmed.status()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(confirmed.hpPayoutStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(BillingCalls.payouts(billingClient)).extracting(PayoutCommand::receiverUserId)
                    .containsExactly(GUEST);
            assertErrorCode(() -> appearanceFacade.getDetail(HOST, appearanceId),
                    AppearanceErrorCode.APPEARANCE_USER_NOT_ACTIVE);
        }

        @Test
        @DisplayName("지급 결과를 모르면 확인은 성공하고 작업은 PENDING → 재시도 배치가 DONE, 다시 실행해도 지급은 한 번")
        void payoutRetried() {
            Long appearanceId = establishFromRequest().appearanceId();
            broadcastEnded(appearanceId);
            BillingStubs.unavailable(billingClient);

            AppearanceResDto confirmed = appearanceFacade.confirm(HOST, appearanceId);

            assertThat(confirmed.status()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(confirmed.hpPayoutStatus()).isEqualTo(HpTaskStatus.PENDING);

            BillingStubs.available(billingClient);
            HpTask task = onlyTask(HpTaskType.PAYOUT);
            retryTimeReached(task);
            hpTaskRetryUseCase.retryDue();
            hpTaskExecuteUseCase.execute(task.getId()); // 이미 DONE이면 다시 실행하지 않는다

            assertThat(appearanceFacade.getDetail(HOST, appearanceId).hpPayoutStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(BillingCalls.payouts(billingClient)).extracting(PayoutCommand::payoutRequestId)
                    .containsExactly(task.getIdempotencyKey());
        }

        @Test
        @DisplayName("Billing이 지급을 거절하면(게스트 지갑 없음) 재시도 없이 DEAD, Billing 오류 코드를 기록한다. 확인은 그대로")
        void payoutRejected() {
            Long appearanceId = establishFromRequest().appearanceId();
            broadcastEnded(appearanceId);
            BillingStubs.reject(billingClient, Rejection.WALLET_NOT_FOUND);

            AppearanceResDto confirmed = appearanceFacade.confirm(HOST, appearanceId);

            assertThat(confirmed.status()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(confirmed.hpPayoutStatus()).isEqualTo(HpTaskStatus.DEAD);
            HpTask task = onlyTask(HpTaskType.PAYOUT);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(task.getLastError()).startsWith("WALLET_NOT_FOUND");
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("불참")
    class Absent {

        @Test
        @DisplayName("요청서 출처: ABSENT·사유, 반환 작업 RELEASE·APPEARANCE_ABSENT·DONE, Billing에 호스트 반환 30,000 요청, 지급 요청 없음")
        void fromRequest() {
            RequestAppearance established = establishFromRequest();
            broadcastEnded(established.appearanceId());

            AppearanceResDto absent = appearanceFacade.markAbsent(HOST, established.appearanceId(),
                    "연락이 되지 않았습니다.");

            assertThat(absent.status()).isEqualTo(AppearanceStatus.ABSENT);
            assertThat(absent.absentReason()).isEqualTo("연락이 되지 않았습니다.");
            assertThat(absent.absentAt()).isNotNull();
            assertThat(absent.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(absent.hpPayoutStatus()).isNull();
            HpTask task = onlyTask(HpTaskType.RELEASE);
            assertThat(task.getReason()).isEqualTo(HpTaskReason.APPEARANCE_ABSENT);
            assertThat(task.getIdempotencyKey()).isEqualTo("APPEARANCE_REQUEST-%d-RELEASE-APPEARANCE-%d"
                    .formatted(established.requestId(), established.appearanceId()));
            assertThat(task.getAmount()).isEqualTo(FEE);
            assertThat(BillingCalls.releases(billingClient)).singleElement().satisfies(release -> {
                assertThat(release.userId()).isEqualTo(HOST);
                assertThat(release.referenceId()).isEqualTo(established.requestId());
                assertThat(release.releaseRequestId()).isEqualTo(task.getIdempotencyKey());
                assertThat(release.amountHp()).isEqualTo(FEE);
            });
            assertThat(BillingCalls.payouts(billingClient)).isEmpty();
        }

        @Test
        @DisplayName("확인과 불참이 동시에 오면 한쪽만 반영된다: HP 작업 1건, 다른 쪽은 APPEARANCE_NOT_CONFIRMABLE 또는 CONCURRENT_UPDATE")
        void concurrentConfirmAndAbsent() throws Exception {
            Long appearanceId = establishFromRequest().appearanceId();
            broadcastEnded(appearanceId);

            List<Object> results = runConcurrently(List.of(
                    () -> appearanceFacade.confirm(HOST, appearanceId),
                    () -> appearanceFacade.markAbsent(HOST, appearanceId, null)));

            List<AppearanceResDto> succeeded = new ArrayList<>();
            for (Object result : results) {
                if (result instanceof AppearanceResDto response) {
                    succeeded.add(response);
                } else {
                    assertThat(result).isInstanceOf(AppearanceException.class)
                            .extracting("errorCode").isIn(AppearanceErrorCode.APPEARANCE_NOT_CONFIRMABLE,
                                    AppearanceErrorCode.CONCURRENT_UPDATE);
                }
            }
            assertThat(succeeded).hasSize(1);
            assertThat(tasksOf(HpTaskType.PAYOUT).size() + tasksOf(HpTaskType.RELEASE).size()).isEqualTo(1);
            if (succeeded.getFirst().status() == AppearanceStatus.CONFIRMED) {
                assertThat(BillingCalls.payouts(billingClient)).hasSize(1);
                assertThat(BillingCalls.releases(billingClient)).isEmpty();
            } else {
                assertThat(BillingCalls.releases(billingClient)).hasSize(1);
                assertThat(BillingCalls.payouts(billingClient)).isEmpty();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("자동 확인 (배치)")
    class AutoConfirm {

        @Test
        @DisplayName("확인 기간이 끝난 성립 출연만 CONFIRMED(AUTO) + 지급 DONE. 하루 늦게 성립해 기간 안인 출연·불참 처리한 출연은 그대로")
        void confirmDue() {
            Long due = establishFromRequest().appearanceId();
            Long recruitId = postRecruit(1);
            Long absent = establishFromRecruit(recruitId, GUEST2);
            clock.advance(Duration.ofDays(1));
            Long notYet = establishFromRequest().appearanceId(); // 방송 예정 일시가 하루 늦다
            recruitDeadlinePassed(recruitId);
            broadcastEnded(absent);
            appearanceFacade.markAbsent(HOST, absent, null);
            confirmPeriodEnded(due); // due·absent는 기간 끝, notYet은 하루 남음

            int confirmed = appearanceAutoConfirmUseCase.confirmDue();

            assertThat(confirmed).isEqualTo(1);
            AppearanceResDto detail = appearanceFacade.getDetail(HOST, due);
            assertThat(detail.status()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(detail.confirmType()).isEqualTo(AppearanceConfirmType.AUTO);
            assertThat(detail.hpPayoutStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(appearanceFacade.getDetail(HOST, notYet).status()).isEqualTo(AppearanceStatus.ESTABLISHED);
            assertThat(appearanceFacade.getDetail(HOST, absent).status()).isEqualTo(AppearanceStatus.ABSENT);
            assertThat(onlyTask(HpTaskType.PAYOUT).getAppearanceId()).isEqualTo(due);
            assertThat(BillingCalls.payouts(billingClient)).extracting(PayoutCommand::sourceId).containsExactly(due);
        }

        @Test
        @DisplayName("취소 요청이 처리 중인 출연은 건너뛰고, 자동 거절로 성립이 된 뒤 다음 실행에서 확인된다. 다시 실행해도 지급은 한 번")
        void afterCancelAutoReject() {
            Long appearanceId = establishFromRequest().appearanceId();
            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");
            confirmPeriodEnded(appearanceId);

            assertThat(appearanceAutoConfirmUseCase.confirmDue()).isZero();

            appearanceCancelAutoRejectUseCase.rejectDue();
            assertThat(appearanceAutoConfirmUseCase.confirmDue()).isEqualTo(1);
            assertThat(appearanceAutoConfirmUseCase.confirmDue()).isZero();
            assertThat(tasksOf(HpTaskType.PAYOUT)).hasSize(1);
            assertThat(BillingCalls.payouts(billingClient)).hasSize(1);
        }

        @Test
        @DisplayName("지급 결과를 모르면 자동 확인은 그대로 하고 지급 작업만 PENDING으로 남는다 (재시도 배치가 이어서 한다)")
        void payoutUnknown() {
            Long appearanceId = establishFromRequest().appearanceId();
            confirmPeriodEnded(appearanceId);
            BillingStubs.unavailable(billingClient);

            assertThat(appearanceAutoConfirmUseCase.confirmDue()).isEqualTo(1);

            AppearanceResDto detail = appearanceFacade.getDetail(HOST, appearanceId);
            assertThat(detail.status()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(detail.hpPayoutStatus()).isEqualTo(HpTaskStatus.PENDING);
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("로그인 역할이 USER면 출연 확인·불참이 막힌다 (@PreAuthorize)")
    void userRole() {
        assertThatThrownBy(() -> appearanceFacade.confirm(HOST, 1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceFacade.markAbsent(HOST, 1L, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("로그인 역할이 ADMIN이어도 출연 확인·불참이 막힌다 (인플루언서만)")
    void adminRole() {
        assertThatThrownBy(() -> appearanceFacade.confirm(HOST, 1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceFacade.markAbsent(HOST, 1L, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** 작업들을 각자 다른 스레드에서 동시에 실행하고, 결과(응답 또는 예외)를 순서대로 모은다 */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(asInfluencer(start, task)));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    /** 다른 스레드에서도 인플루언서로 로그인해 실행하고, 예외는 결과로 돌려준다 */
    private Callable<Object> asInfluencer(CountDownLatch start, Callable<Object> task) {
        return () -> {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", null,
                    List.of(new SimpleGrantedAuthority("ROLE_INFLUENCER"))));
            try {
                start.await();
                return task.call();
            } catch (RuntimeException exception) {
                return exception;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }
}
