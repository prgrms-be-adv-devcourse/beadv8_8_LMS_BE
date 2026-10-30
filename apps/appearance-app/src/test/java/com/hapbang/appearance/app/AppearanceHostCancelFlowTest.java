package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.domain.AppearanceCancelRequestStatus;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRole;
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
import com.hapbang.shared.billing.dto.PenaltyCommand;
import com.hapbang.shared.billing.dto.ReleaseCommand;
import com.hapbang.shared.billing.domain.BillingReferenceType;

/**
 * 호스트 일방 취소·모집 전체 취소를 DB와 Billing 대역으로 확인한다 (ISSUE.md APR-32).
 * 출연료 30,000 HP → 위약금 9,000(게스트) + 반환 21,000(호스트). Billing은 대역이라 보낸 정산 요청 내용을 본다
 * (실제 잔액 변화는 apps 통합 테스트).
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceHostCancelFlowTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long GUEST3 = 4L;
    private static final Long OTHER = 5L;
    private static final long FEE = 30_000L;
    private static final long PENALTY = 9_000L;

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
    HpTaskRepository hpTaskRepository;

    @Autowired
    HpTaskRetryUseCase hpTaskRetryUseCase;

    @Autowired
    AppearanceRecruitDeadlineCloseUseCase appearanceRecruitDeadlineCloseUseCase;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @MockitoSpyBean
    HpTaskSupport hpTaskSupport;

    @Autowired
    BillingClient billingClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, GUEST2, GUEST3, OTHER)) {
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

    private HpTask onlyTask(HpTaskType type) {
        List<HpTask> tasks = new ArrayList<>();
        for (HpTask task : hpTaskRepository.findAll()) {
            if (task.getTaskType() == type) {
                tasks.add(task);
            }
        }
        assertThat(tasks).hasSize(1);
        return tasks.getFirst();
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

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private record RequestAppearance(Long requestId, Long appearanceId) {
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 일방 취소")
    class HostCancel {

        @Test
        @DisplayName("요청서 출처: CANCELED_BY_HOST, Billing에 위약금 9,000 게스트 지급 + 21,000 호스트 반환(합계 = 출연료 30,000) 요청 (PENALTY·HOST_CANCELED·DONE)")
        void fromRequest() {
            RequestAppearance established = establishFromRequest();

            AppearanceResDto canceled = appearanceFacade.hostCancel(HOST, established.appearanceId(), false,
                    "방송이 취소됐습니다.");

            assertThat(canceled.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(canceled.penaltyAmount()).isEqualTo(PENALTY);
            assertThat(canceled.hostCancelReason()).isEqualTo("방송이 취소됐습니다.");
            assertThat(canceled.hpPenaltyStatus()).isEqualTo(HpTaskStatus.DONE);
            HpTask task = onlyTask(HpTaskType.PENALTY);
            assertThat(task.getReason()).isEqualTo(HpTaskReason.HOST_CANCELED);
            assertThat(task.getIdempotencyKey()).isEqualTo("APPEARANCE_REQUEST-%d-PENALTY-APPEARANCE-%d"
                    .formatted(established.requestId(), established.appearanceId()));
            assertThat(BillingCalls.penalties(billingClient)).singleElement().satisfies(penalty -> {
                assertThat(penalty.payerUserId()).isEqualTo(HOST);
                assertThat(penalty.referenceType()).isEqualTo(BillingReferenceType.APPEARANCE_REQUEST);
                assertThat(penalty.referenceId()).isEqualTo(established.requestId());
                assertThat(penalty.cancellationId()).isEqualTo(task.getIdempotencyKey());
                assertThat(penalty.receiverUserId()).isEqualTo(GUEST);
                assertThat(penalty.sourceType()).isEqualTo(SettlementSourceType.APPEARANCE_REQUEST);
                assertThat(penalty.sourceId()).isEqualTo(established.appearanceId());
                assertThat(penalty.releaseAmountHp()).isEqualTo(FEE - PENALTY);
                assertThat(penalty.payoutAmountHp()).isEqualTo(PENALTY);
            });
        }

        @Test
        @DisplayName("처리 중인 취소 요청: 확인 없으면 CANCEL_REQUEST_CONFIRM_REQUIRED(아무것도 안 바뀜), 확인하면 취소 + 요청 CLOSED_BY_HOST_CANCEL + 위약금 지급")
        void pendingCancelRequest() {
            Long appearanceId = establishFromRequest().appearanceId();
            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");

            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, appearanceId, false, null),
                    AppearanceErrorCode.CANCEL_REQUEST_CONFIRM_REQUIRED);
            assertThat(appearanceFacade.getDetail(HOST, appearanceId).status())
                    .isEqualTo(AppearanceStatus.CANCEL_REQUESTED);
            assertThat(hpTaskRepository.count()).isZero();

            AppearanceResDto canceled = appearanceFacade.hostCancel(HOST, appearanceId, true, null);

            assertThat(canceled.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(canceled.cancelRequests().getFirst().status())
                    .isEqualTo(AppearanceCancelRequestStatus.CLOSED_BY_HOST_CANCEL);
            assertThat(BillingCalls.penalties(billingClient)).extracting(PenaltyCommand::payoutAmountHp)
                    .containsExactly(PENALTY);
        }

        @Test
        @DisplayName("거부: 게스트 NOT_APPEARANCE_HOST, 다른 회원 APPEARANCE_NOT_FOUND, 방송 예정 일시 지남 APPEARANCE_SCHEDULE_PASSED, 합의 취소된 출연·다시 취소 APPEARANCE_NOT_CANCELABLE")
        void rejected() {
            Long appearanceId = establishFromRequest().appearanceId();
            assertErrorCode(() -> appearanceFacade.hostCancel(GUEST, appearanceId, false, null),
                    AppearanceErrorCode.NOT_APPEARANCE_HOST);
            assertErrorCode(() -> appearanceFacade.hostCancel(OTHER, appearanceId, false, null),
                    AppearanceErrorCode.APPEARANCE_NOT_FOUND);
            appearanceFacade.hostCancel(HOST, appearanceId, false, null);
            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, appearanceId, false, null),
                    AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);

            Long agreed = establishFromRecruit(postRecruit(1), GUEST2);
            appearanceFacade.requestCancel(GUEST2, agreed, "사유");
            appearanceFacade.acceptCancel(HOST, agreed);
            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, agreed, true, null),
                    AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);

            Long passed = establishFromRecruit(postRecruit(1), GUEST3);
            clock.advanceTo(jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                    LocalDateTime.class, passed));
            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, passed, false, null),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);
        }

        @Test
        @DisplayName("방송 뒤 확인(CONFIRMED)·불참(ABSENT)한 출연은 일방 취소할 수 없다 (APPEARANCE_NOT_CANCELABLE), 위약금 작업 없음")
        void confirmedOrAbsent() {
            Long confirmed = establishFromRequest().appearanceId();
            Long absent = establishFromRecruit(postRecruit(1), GUEST2);
            clock.advanceTo(jdbcTemplate.queryForObject("select max(scheduled_at) from appearances.appearances",
                    LocalDateTime.class));
            appearanceFacade.confirm(HOST, confirmed);
            appearanceFacade.markAbsent(HOST, absent, null);

            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, confirmed, false, null),
                    AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);
            assertErrorCode(() -> appearanceFacade.hostCancel(HOST, absent, false, null),
                    AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE);
            assertThat(tasksOf(HpTaskType.PENALTY)).isEmpty();
            assertThat(BillingCalls.penalties(billingClient)).isEmpty();
        }

        @Test
        @DisplayName("모집 출처: 그 자리 보류(30,000)가 위약금·반환으로 정산되고, 성립 인원은 그대로라 자리가 다시 열리지 않는다")
        void fromRecruitSeatNotReopened() {
            Long recruitId = postRecruit(1);
            Long appearanceId = establishFromRecruit(recruitId, GUEST);
            Long waiting = appearanceApplicationFacade.apply(GUEST2, recruitId, "저도요").id();

            appearanceFacade.hostCancel(HOST, appearanceId, false, null);

            assertThat(BillingCalls.penalties(billingClient)).singleElement().satisfies(penalty -> {
                assertThat(penalty.referenceType()).isEqualTo(BillingReferenceType.APPEARANCE_RECRUIT);
                assertThat(penalty.sourceType()).isEqualTo(SettlementSourceType.RECRUIT_APPEARANCE);
                assertThat(penalty.payoutAmountHp()).isEqualTo(FEE * 30 / 100);
                assertThat(penalty.releaseAmountHp()).isEqualTo(FEE - FEE * 30 / 100);
            });
            assertThat(onlyTask(HpTaskType.PENALTY).getIdempotencyKey())
                    .isEqualTo("APPEARANCE_RECRUIT-%d-PENALTY-APPEARANCE-%d".formatted(recruitId, appearanceId));
            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).selectedCount()).isEqualTo(1);
            assertErrorCode(() -> appearanceApplicationFacade.accept(HOST, waiting), AppearanceErrorCode.RECRUIT_FULL);
        }

        @Test
        @DisplayName("정산 결과를 모르면 취소는 성공하고 작업은 PENDING → 재시도 배치가 DONE으로 처리하며, 다시 실행해도 위약금은 한 번만 지급된다")
        void penaltyRetried() {
            Long appearanceId = establishFromRequest().appearanceId();
            BillingStubs.unavailable(billingClient);

            AppearanceResDto canceled = appearanceFacade.hostCancel(HOST, appearanceId, false, null);

            assertThat(canceled.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(canceled.hpPenaltyStatus()).isEqualTo(HpTaskStatus.PENDING);

            BillingStubs.available(billingClient);
            HpTask task = onlyTask(HpTaskType.PENALTY);
            clock.advanceTo(task.getNextAttemptAt()); // 다음 시도 시각이 된다
            hpTaskRetryUseCase.retryDue();
            hpTaskExecuteUseCase.execute(task.getId()); // 이미 DONE이면 다시 실행하지 않는다

            assertThat(appearanceFacade.getDetail(HOST, appearanceId).hpPenaltyStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(BillingCalls.penalties(billingClient)).extracting(PenaltyCommand::cancellationId)
                    .containsExactly(task.getIdempotencyKey());
        }

        @Test
        @DisplayName("Billing이 위약금 정산을 거절하면(게스트 지갑 없음) 재시도 없이 DEAD, Billing 오류 코드를 기록한다. 취소는 그대로")
        void penaltyRejected() {
            Long appearanceId = establishFromRequest().appearanceId();
            BillingStubs.reject(billingClient, Rejection.WALLET_NOT_FOUND);

            AppearanceResDto canceled = appearanceFacade.hostCancel(HOST, appearanceId, false, null);

            assertThat(canceled.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
            assertThat(canceled.hpPenaltyStatus()).isEqualTo(HpTaskStatus.DEAD);
            HpTask task = onlyTask(HpTaskType.PENALTY);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(task.getLastError()).startsWith("WALLET_NOT_FOUND");
        }

        @Test
        @DisplayName("일방 취소(확인함)와 합의 취소 수락이 동시에 와도 한쪽만 반영된다: 위약금 정산 또는 전액 반환 중 하나만 Billing에 요청")
        void concurrentHostCancelAndAgreedCancel() throws Exception {
            Long appearanceId = establishFromRequest().appearanceId();
            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");

            List<Object> results = runConcurrently(List.of(
                    () -> appearanceFacade.hostCancel(HOST, appearanceId, true, null),
                    () -> appearanceFacade.acceptCancel(HOST, appearanceId)));

            List<AppearanceResDto> succeeded = new ArrayList<>();
            for (Object result : results) {
                if (result instanceof AppearanceResDto response) {
                    succeeded.add(response);
                } else {
                    assertThat(result).isInstanceOf(AppearanceException.class).extracting("errorCode")
                            .isIn(AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE,
                                    AppearanceErrorCode.CANCEL_REQUEST_NOT_PENDING, AppearanceErrorCode.CONCURRENT_UPDATE);
                }
            }
            assertThat(succeeded).hasSize(1);
            AppearanceResDto detail = appearanceFacade.getDetail(HOST, appearanceId);
            assertThat(detail.cancelRequests()).singleElement().extracting(request -> request.status())
                    .isNotEqualTo(AppearanceCancelRequestStatus.REQUESTED);
            if (detail.status() == AppearanceStatus.CANCELED_BY_HOST) {
                assertThat(detail.cancelRequests().getFirst().status())
                        .isEqualTo(AppearanceCancelRequestStatus.CLOSED_BY_HOST_CANCEL);
                assertThat(tasksOf(HpTaskType.PENALTY)).hasSize(1);
                assertThat(tasksOf(HpTaskType.RELEASE)).isEmpty();
                assertThat(BillingCalls.releases(billingClient)).isEmpty();
            } else {
                assertThat(detail.status()).isEqualTo(AppearanceStatus.CANCELED_BY_AGREEMENT);
                assertThat(detail.cancelRequests().getFirst().status()).isEqualTo(AppearanceCancelRequestStatus.ACCEPTED);
                assertThat(tasksOf(HpTaskType.RELEASE)).hasSize(1);
                assertThat(tasksOf(HpTaskType.PENALTY)).isEmpty();
                assertThat(BillingCalls.penalties(billingClient)).isEmpty();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("모집 전체 취소")
    class RecruitCancel {

        @Test
        @DisplayName("모집 중, 3명 중 2명 성립·1명 대기: CANCELED, 출연 2건 위약금 9,000씩, 대기 지원 NOT_SELECTED, 빈자리 30,000 반환 (Billing 요청 합계 = 보류 90,000)")
        void recruiting() {
            Long recruitId = postRecruit(3);
            Long first = establishFromRecruit(recruitId, GUEST);
            Long second = establishFromRecruit(recruitId, GUEST2);
            appearanceApplicationFacade.apply(GUEST3, recruitId, "지원합니다.");

            AppearanceRecruitResDto canceled = appearanceRecruitFacade.cancel(HOST, recruitId, false, "방송 취소");

            assertThat(canceled.status()).isEqualTo(AppearanceRecruitStatus.CANCELED);
            assertThat(canceled.cancelReason()).isEqualTo("방송 취소");
            assertThat(canceled.canceledAt()).isNotNull();
            assertThat(canceled.hpReleaseStatus()).isEqualTo(HpTaskStatus.DONE);
            for (Long appearanceId : List.of(first, second)) {
                AppearanceResDto detail = appearanceFacade.getDetail(HOST, appearanceId);
                assertThat(detail.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
                assertThat(detail.penaltyAmount()).isEqualTo(PENALTY);
                assertThat(detail.hostCancelReason()).isEqualTo("방송 취소");
                assertThat(detail.hpPenaltyStatus()).isEqualTo(HpTaskStatus.DONE);
            }
            assertThat(appearanceApplicationFacade.getMine(GUEST3, null, PageRequest.of(0, 20)).getContent()
                    .getFirst().status()).isEqualTo(AppearanceApplicationStatus.NOT_SELECTED);
            HpTask vacancy = onlyTask(HpTaskType.RELEASE);
            assertThat(vacancy.getReason()).isEqualTo(HpTaskReason.RECRUIT_CANCELED);
            assertThat(vacancy.getAmount()).isEqualTo(FEE);
            assertThat(BillingCalls.penalties(billingClient))
                    .extracting(PenaltyCommand::receiverUserId, PenaltyCommand::payoutAmountHp)
                    .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(GUEST, PENALTY),
                            org.assertj.core.groups.Tuple.tuple(GUEST2, PENALTY));
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::amountHp).containsExactly(FEE);
        }

        @Test
        @DisplayName("앞 작업의 결과 기록이 실패해도(DB 오류 등) 예외 없이 취소가 성공으로 응답되고 뒤 작업도 실행된다. 기록 못 한 작업은 PENDING → 재시도로 DONE, 위약금은 한 번만")
        void executeContinuesAfterRecordFailure() {
            Long recruitId = postRecruit(2);
            establishFromRecruit(recruitId, GUEST);
            // 실행 순서: 위약금 작업(조회 → 결과 기록), 빈자리 반환(조회 → 결과 기록). 위약금 작업의 결과 기록만 실패시킨다
            doCallRealMethod().doThrow(new IllegalStateException("DB 연결 끊김")).doCallRealMethod()
                    .when(hpTaskSupport).getById(any());

            AppearanceRecruitResDto canceled = appearanceRecruitFacade.cancel(HOST, recruitId, false, null);

            assertThat(canceled.status()).isEqualTo(AppearanceRecruitStatus.CANCELED);
            assertThat(onlyTask(HpTaskType.PENALTY).getStatus()).isEqualTo(HpTaskStatus.PENDING);
            assertThat(onlyTask(HpTaskType.RELEASE).getStatus()).isEqualTo(HpTaskStatus.DONE);

            HpTask penalty = onlyTask(HpTaskType.PENALTY);
            clock.advance(Duration.ofMinutes(2)); // 한 번도 기록하지 못한 작업은 배치 대기 시간(1분)이 지나야 재시도 대상
            hpTaskRetryUseCase.retryDue();

            assertThat(onlyTask(HpTaskType.PENALTY).getStatus()).isEqualTo(HpTaskStatus.DONE);
            // 처음 실행은 Billing까지 갔고 결과 기록만 실패했다. 재시도는 같은 취소 ID로 다시 보내 Billing이 한 번만 처리한다
            assertThat(BillingCalls.penalties(billingClient)).extracting(PenaltyCommand::cancellationId)
                    .containsExactly(penalty.getIdempotencyKey(), penalty.getIdempotencyKey());
        }

        @Test
        @DisplayName("성립 0명: 위약금 없이 전액 90,000 반환")
        void noneSelected() {
            Long recruitId = postRecruit(3);

            appearanceRecruitFacade.cancel(HOST, recruitId, false, null);

            assertThat(tasksOf(HpTaskType.PENALTY)).isEmpty();
            assertThat(onlyTask(HpTaskType.RELEASE).getAmount()).isEqualTo(3 * FEE);
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::amountHp).containsExactly(3 * FEE);
        }

        @Test
        @DisplayName("마감 뒤 취소: 빈자리는 마감 때 이미 반환돼 위약금 정산만 요청한다 (반환 요청은 마감 때 1건뿐)")
        void afterClose() {
            Long recruitId = postRecruit(2);
            establishFromRecruit(recruitId, GUEST);
            appearanceRecruitFacade.closeEarly(HOST, recruitId); // 빈자리 30,000 반환

            appearanceRecruitFacade.cancel(HOST, recruitId, false, null);

            assertThat(tasksOf(HpTaskType.PENALTY)).hasSize(1);
            assertThat(tasksOf(HpTaskType.RELEASE)).extracting(HpTask::getReason)
                    .containsExactly(HpTaskReason.RECRUIT_CLOSED);
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::amountHp).containsExactly(FEE);
            assertThat(BillingCalls.penalties(billingClient)).extracting(PenaltyCommand::payoutAmountHp)
                    .containsExactly(PENALTY);
        }

        @Test
        @DisplayName("모집 중 합의 취소로 빈 자리는 빈자리 반환에 들어가고 위약금은 없다 (2명 모집, 1명 성립 후 합의 취소 → 60,000 반환)")
        void afterAgreedCancel() {
            Long recruitId = postRecruit(2);
            Long agreed = establishFromRecruit(recruitId, GUEST);
            appearanceFacade.requestCancel(GUEST, agreed, "사유");
            appearanceFacade.acceptCancel(HOST, agreed);

            appearanceRecruitFacade.cancel(HOST, recruitId, false, null);

            assertThat(tasksOf(HpTaskType.PENALTY)).isEmpty();
            assertThat(onlyTask(HpTaskType.RELEASE).getAmount()).isEqualTo(2 * FEE);
            assertThat(BillingCalls.releases(billingClient)).extracting(ReleaseCommand::amountHp)
                    .containsExactly(2 * FEE);
        }

        @Test
        @DisplayName("처리 중인 취소 요청이 하나라도 있으면 확인 없이 CANCEL_REQUEST_CONFIRM_REQUIRED (아무것도 안 바뀜), 확인하면 전체 취소")
        void pendingCancelRequest() {
            Long recruitId = postRecruit(2);
            establishFromRecruit(recruitId, GUEST);
            Long requesting = establishFromRecruit(recruitId, GUEST2);
            appearanceFacade.requestCancel(GUEST2, requesting, "사유");

            assertErrorCode(() -> appearanceRecruitFacade.cancel(HOST, recruitId, false, null),
                    AppearanceErrorCode.CANCEL_REQUEST_CONFIRM_REQUIRED);
            assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).status())
                    .isEqualTo(AppearanceRecruitStatus.RECRUITING);
            assertThat(appearanceFacade.getMine(HOST, AppearanceRole.HOST, List.of(AppearanceStatus.CANCELED_BY_HOST),
                    PageRequest.of(0, 20)).getTotalElements()).isZero();

            appearanceRecruitFacade.cancel(HOST, recruitId, true, null);

            assertThat(appearanceFacade.getDetail(HOST, requesting).cancelRequests().getFirst().status())
                    .isEqualTo(AppearanceCancelRequestStatus.CLOSED_BY_HOST_CANCEL);
            assertThat(tasksOf(HpTaskType.PENALTY)).hasSize(2);
        }

        @Test
        @DisplayName("거부: 다른 회원 NOT_RECRUIT_HOST, 다시 취소 APPEARANCE_RECRUIT_NOT_CANCELABLE, 방송 예정 일시 지남 APPEARANCE_SCHEDULE_PASSED, 보류 처리 중 APPEARANCE_RECRUIT_NOT_FOUND")
        void rejected() {
            Long recruitId = postRecruit(1);
            assertErrorCode(() -> appearanceRecruitFacade.cancel(OTHER, recruitId, false, null),
                    AppearanceErrorCode.NOT_RECRUIT_HOST);
            appearanceRecruitFacade.cancel(HOST, recruitId, false, null);
            assertErrorCode(() -> appearanceRecruitFacade.cancel(HOST, recruitId, false, null),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_CANCELABLE);

            Long passed = postRecruit(1);
            // 실제 순서: 모집 마감 일시 → 기한 마감 배치(빈자리 반환) → 방송 예정 일시
            clock.advanceTo(jdbcTemplate.queryForObject("select recruit_deadline from appearances.recruits where id = ?",
                    LocalDateTime.class, passed));
            appearanceRecruitDeadlineCloseUseCase.closeDue();
            clock.advanceTo(jdbcTemplate.queryForObject("select scheduled_at from appearances.recruits where id = ?",
                    LocalDateTime.class, passed));
            assertErrorCode(() -> appearanceRecruitFacade.cancel(HOST, passed, false, null),
                    AppearanceErrorCode.APPEARANCE_SCHEDULE_PASSED);

            BillingStubs.unavailable(billingClient);
            assertThatThrownBy(() -> postRecruit(1))
                    .isInstanceOfSatisfying(AppearanceException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.BILLING_UNAVAILABLE));
            BillingStubs.available(billingClient);
            Long pendingId = jdbcTemplate.queryForObject(
                    "select id from appearances.recruits where status = 'HOLD_PENDING'", Long.class);
            assertErrorCode(() -> appearanceRecruitFacade.cancel(HOST, pendingId, false, null),
                    AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_FOUND);
        }

        @Test
        @DisplayName("모집 전체 취소(확인 없음)와 게스트 취소 요청이 동시에 오면 한쪽만 반영된다: 취소가 먼저면 위약금 정산, "
                + "요청이 먼저면 취소는 CANCEL_REQUEST_CONFIRM_REQUIRED로 막히고 아무것도 바뀌지 않는다")
        void concurrentCancelAndGuestCancelRequest() throws Exception {
            Long recruitId = postRecruit(1);
            Long appearanceId = establishFromRecruit(recruitId, GUEST);

            List<Object> results = runConcurrently(List.of(
                    () -> appearanceRecruitFacade.cancel(HOST, recruitId, false, null),
                    () -> appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.")));

            AppearanceResDto detail = appearanceFacade.getDetail(HOST, appearanceId);
            if (results.getFirst() instanceof AppearanceRecruitResDto canceled) {
                assertThat(canceled.status()).isEqualTo(AppearanceRecruitStatus.CANCELED);
                assertThat(results.get(1)).isInstanceOf(AppearanceException.class).extracting("errorCode")
                        .isIn(AppearanceErrorCode.APPEARANCE_NOT_CANCELABLE, AppearanceErrorCode.CONCURRENT_UPDATE);
                assertThat(detail.status()).isEqualTo(AppearanceStatus.CANCELED_BY_HOST);
                assertThat(detail.cancelRequests()).isEmpty();
                assertThat(tasksOf(HpTaskType.PENALTY)).hasSize(1);
            } else {
                assertThat(results.getFirst()).isInstanceOf(AppearanceException.class).extracting("errorCode")
                        .isIn(AppearanceErrorCode.CANCEL_REQUEST_CONFIRM_REQUIRED, AppearanceErrorCode.CONCURRENT_UPDATE);
                assertThat(results.get(1)).isInstanceOf(AppearanceResDto.class);
                assertThat(appearanceRecruitFacade.getDetail(HOST, recruitId).status())
                        .isEqualTo(AppearanceRecruitStatus.RECRUITING);
                assertThat(detail.status()).isEqualTo(AppearanceStatus.CANCEL_REQUESTED);
                assertThat(hpTaskRepository.count()).isZero();
                assertThat(BillingCalls.penalties(billingClient)).isEmpty();
            }
        }

        @Test
        @DisplayName("모집 전체 취소와 지원 수락이 동시에 와도 교착 상태가 없고, 취소됐으면 진행 중인 출연이 남지 않으며 Billing 요청 합계 = 보류 60,000")
        void concurrentCancelAndAccept() throws Exception {
            Long recruitId = postRecruit(2);
            establishFromRecruit(recruitId, GUEST);
            Long waiting = appearanceApplicationFacade.apply(GUEST2, recruitId, "지원합니다.").id();

            List<Object> results = runConcurrently(List.of(
                    () -> appearanceRecruitFacade.cancel(HOST, recruitId, false, null),
                    () -> appearanceApplicationFacade.accept(HOST, waiting)));

            assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOfAny(
                    AppearanceRecruitResDto.class, AppearanceApplicationResDto.class, AppearanceException.class));
            assertThat(results.getFirst()).isInstanceOf(AppearanceRecruitResDto.class);
            if (results.get(1) instanceof AppearanceException error) {
                // 전체 취소가 먼저: 수락은 막히고 성립 출연 1건만 일방 취소, 빈자리 1명분 반환
                assertThat(error.getErrorCode()).isEqualTo(AppearanceErrorCode.APPEARANCE_RECRUIT_NOT_OPEN);
                assertThat(tasksOf(HpTaskType.PENALTY)).hasSize(1);
                assertThat(onlyTask(HpTaskType.RELEASE).getAmount()).isEqualTo(FEE);
            } else {
                // 수락이 먼저: 출연 2건 모두 일방 취소, 빈자리 없음
                assertThat(results.get(1)).isInstanceOf(AppearanceApplicationResDto.class);
                assertThat(tasksOf(HpTaskType.PENALTY)).hasSize(2);
                assertThat(tasksOf(HpTaskType.RELEASE)).isEmpty();
            }
            assertThat(appearanceFacade.getMine(HOST, AppearanceRole.HOST,
                    List.of(AppearanceStatus.ESTABLISHED, AppearanceStatus.CANCEL_REQUESTED), PageRequest.of(0, 20))
                    .getTotalElements()).isZero();
            // 모집글 보류(2명분 60,000)를 위약금 정산·빈자리 반환으로 모두 처리해 달라고 보냈다
            long settled = 0;
            for (PenaltyCommand penalty : BillingCalls.penalties(billingClient)) {
                settled += penalty.releaseAmountHp() + penalty.payoutAmountHp();
            }
            for (ReleaseCommand release : BillingCalls.releases(billingClient)) {
                settled += release.amountHp();
            }
            assertThat(settled).isEqualTo(2 * FEE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("로그인 역할이 USER면 일방 취소·모집 전체 취소가 막힌다")
    void userRole() {
        assertThatThrownBy(() -> appearanceFacade.hostCancel(HOST, 1L, false, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceRecruitFacade.cancel(HOST, 1L, false, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("로그인 역할이 ADMIN이어도 막힌다 (인플루언서만)")
    void adminRole() {
        assertThatThrownBy(() -> appearanceFacade.hostCancel(HOST, 1L, false, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> appearanceRecruitFacade.cancel(HOST, 1L, false, null))
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
