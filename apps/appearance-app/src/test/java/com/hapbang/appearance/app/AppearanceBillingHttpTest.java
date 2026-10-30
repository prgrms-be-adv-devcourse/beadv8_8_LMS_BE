package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceResDto;
import com.hapbang.appearance.app.dto.HpTaskAdminResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceHoldRejectedException;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.AppearanceStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskStatus;
import com.hapbang.appearance.domain.HpTaskType;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.out.HpTaskRepository;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.MockBilling;
import com.hapbang.appearance.support.MockBilling.Endpoint;
import com.hapbang.appearance.support.MockBilling.ReceivedRequest;
import com.hapbang.appearance.support.MockBillingIntegrationTest;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 지갑 HTTP 연동 시나리오 (#274). 운영과 같은 {@code BillingClient} 구현이 HTTP로 Mock Billing 서버를 부른다.
 * 지갑 응답(성공·재전송·거절·지연·장애)마다 출연 업무 상태·HP 작업·사용자 응답이 맞는지 확인한다.
 * 출연 업무 규칙 자체(어떤 금액을 언제 보내는지)는 Mockito 대역을 쓰는 흐름 테스트가 확인한다.
 */
@WithMockUser(roles = "INFLUENCER")
class AppearanceBillingHttpTest extends MockBillingIntegrationTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long ADMIN = 900L;
    private static final long FEE = AppearanceRequestFixture.FEE;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceHoldRecheckUseCase appearanceHoldRecheckUseCase;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @Autowired
    HpTaskAdminFacade hpTaskAdminFacade;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.user(ADMIN, Role.ADMIN, UserStatus.ACTIVE));
    }

    private AppearanceRequestResDto send() {
        return appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(clock.now()));
    }

    private AppearanceRequestStatus requestStatus() {
        return appearanceRequestRepository.findAll().getFirst().getStatus();
    }

    /** 요청서를 보내고 게스트가 수락해 성립한 출연 ID */
    private Long establish() {
        AppearanceRequestResDto sent = send();
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    /** 방송이 끝나 확인 기간에 들어선다 */
    private void broadcastEnded(Long appearanceId) {
        clock.advanceTo(jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId).plusHours(1));
    }

    /** 출연을 확인해 지급 작업을 만든다 (작업은 확인 직후 한 번 실행된다) */
    private HpTask confirm() {
        Long appearanceId = establish();
        broadcastEnded(appearanceId);
        appearanceFacade.confirm(HOST, appearanceId);
        return onlyTask(HpTaskType.PAYOUT);
    }

    private HpTask onlyTask(HpTaskType type) {
        List<HpTask> tasks = hpTaskRepository.findAll().stream().filter(task -> task.getTaskType() == type).toList();
        assertThat(tasks).hasSize(1);
        return tasks.getFirst();
    }

    private HpTask reload(HpTask task) {
        return hpTaskRepository.findById(task.getId()).orElseThrow();
    }

    /** 실패한 작업의 다음 시도 시각이 되어 다시 실행한다 */
    private HpTask runAgain(HpTask task) {
        clock.advanceTo(reload(task).getNextAttemptAt());
        hpTaskExecuteUseCase.execute(task.getId());
        return reload(task);
    }

    /** 보류 결과를 받지 못한 요청서·모집글을 대기 시간이 지난 뒤 재확인한다 */
    private int recheckLater() {
        clock.advance(Duration.ofMinutes(2));
        return appearanceHoldRecheckUseCase.recheckDue();
    }

    private static void assertErrorCode(Runnable action, AppearanceErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("보류 (요청서 보내기·모집글 올리기)")
    class Hold {

        @Test
        @DisplayName("성공: REQUESTED. 지갑은 내부 API 키·JSON으로 호스트·요청서·출연료 보류 요청을 받는다")
        void succeeded() {
            AppearanceRequestResDto sent = send();

            assertThat(sent.status()).isEqualTo(AppearanceRequestStatus.REQUESTED);
            assertThat(MockBilling.requests(Endpoint.HOLD)).singleElement().satisfies(hold -> {
                assertThat(hold.apiKey()).isEqualTo(IntegrationTest.INTERNAL_API_KEY);
                assertThat(hold.contentType()).startsWith("application/json");
                assertThat(hold.number("userId")).isEqualTo(HOST);
                assertThat(hold.text("referenceType")).isEqualTo("APPEARANCE_REQUEST");
                assertThat(hold.number("referenceId")).isEqualTo(sent.id());
                assertThat(hold.number("amountHp")).isEqualTo(FEE);
            });
        }

        @Test
        @DisplayName("잔액 부족(409): 409 INSUFFICIENT_BALANCE를 출연 문구로 응답, 요청서 HOLD_FAILED")
        void insufficientBalance() {
            MockBilling.fail(Endpoint.HOLD, 409, "INSUFFICIENT_BALANCE", "충전 HP 1,000이 부족합니다(지갑 설명).");

            assertThatThrownBy(AppearanceBillingHttpTest.this::send)
                    .isInstanceOf(AppearanceHoldRejectedException.class)
                    .hasMessage(AppearanceErrorCode.INSUFFICIENT_BALANCE.getMessage())
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.INSUFFICIENT_BALANCE);
            assertThat(requestStatus()).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
        }

        @Test
        @DisplayName("목록 밖 코드(401 내부 API 키 오류): 500 HP_HOLD_FAILED, 지갑 코드·설명은 응답에 나가지 않음, HOLD_FAILED")
        void unmappedHidden() {
            MockBilling.fail(Endpoint.HOLD, 401, "INVALID_INTERNAL_API_KEY", "내부 API 키가 올바르지 않습니다.");

            assertThatThrownBy(AppearanceBillingHttpTest.this::send)
                    .isInstanceOf(AppearanceHoldRejectedException.class)
                    .hasMessage(AppearanceErrorCode.HP_HOLD_FAILED.getMessage())
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.HP_HOLD_FAILED);
            assertThat(requestStatus()).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
        }

        @Test
        @DisplayName("같은 키·다른 내용(409 IDEMPOTENCY_KEY_CONFLICT): 500 HP_HOLD_FAILED, HOLD_FAILED")
        void idempotencyConflict() {
            MockBilling.fail(Endpoint.HOLD, 409, "IDEMPOTENCY_KEY_CONFLICT", "같은 참조 대상으로 다른 보류를 요청했습니다.");

            assertErrorCode(AppearanceBillingHttpTest.this::send, AppearanceErrorCode.HP_HOLD_FAILED);
            assertThat(requestStatus()).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
        }

        @Test
        @DisplayName("5xx: 503 BILLING_UNAVAILABLE, HOLD_PENDING → 지갑이 돌아오면 재확인 배치가 같은 참조 대상으로 다시 보내 REQUESTED")
        void serverErrorThenRecheck() {
            MockBilling.fail(Endpoint.HOLD, 500, "HOLD_STATE_INCONSISTENT", null);

            assertErrorCode(AppearanceBillingHttpTest.this::send, AppearanceErrorCode.BILLING_UNAVAILABLE);
            assertThat(requestStatus()).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);

            MockBilling.succeed(Endpoint.HOLD);
            assertThat(recheckLater()).isEqualTo(1);

            assertThat(requestStatus()).isEqualTo(AppearanceRequestStatus.REQUESTED);
            assertThat(MockBilling.requests(Endpoint.HOLD)).hasSize(2)
                    .extracting(hold -> hold.number("referenceId")).containsOnly(
                            appearanceRequestRepository.findAll().getFirst().getId());
        }

        @Test
        @DisplayName("응답 시간 초과인데 지갑은 처리함: BILLING_UNAVAILABLE·HOLD_PENDING → 재확인에서 처음 결과(replayed)로 REQUESTED")
        void timeoutButProcessed() {
            MockBilling.delay(Endpoint.HOLD, 2_000);

            assertErrorCode(AppearanceBillingHttpTest.this::send, AppearanceErrorCode.BILLING_UNAVAILABLE);
            assertThat(requestStatus()).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);
            assertThat(MockBilling.requests(Endpoint.HOLD)).hasSize(1); // 지갑은 받았다

            MockBilling.succeed(Endpoint.HOLD);
            assertThat(recheckLater()).isEqualTo(1);

            assertThat(requestStatus()).isEqualTo(AppearanceRequestStatus.REQUESTED);
        }

        @Test
        @DisplayName("요청 한도 초과(429, 처리하지 않음): 공용 Client가 짧게 다시 보낸 뒤에도 실패하면 BILLING_UNAVAILABLE, 모집글 HOLD_PENDING")
        void tooManyRequests() {
            MockBilling.fail(Endpoint.HOLD, 429, null, null);

            assertErrorCode(() -> appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(clock.now())),
                    AppearanceErrorCode.BILLING_UNAVAILABLE);

            assertThat(appearanceRecruitRepository.findAll().getFirst().getStatus())
                    .isEqualTo(AppearanceRecruitStatus.HOLD_PENDING);
            assertThat(MockBilling.requests(Endpoint.HOLD)).isNotEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("HP 작업 (반환·지급·위약금)")
    class Task {

        @Test
        @DisplayName("반환 성공: 게스트가 거절하면 반환 작업 DONE. 지갑은 작업의 멱등 키를 반환 요청 ID로, 출연료 전액을 받는다")
        void releaseSucceeded() {
            AppearanceRequestResDto sent = send();

            appearanceRequestFacade.reject(GUEST, sent.id(), null);

            HpTask task = onlyTask(HpTaskType.RELEASE);
            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(MockBilling.requests(Endpoint.RELEASE)).singleElement().satisfies(release -> {
                assertThat(release.requestId(Endpoint.RELEASE)).isEqualTo(task.getIdempotencyKey());
                assertThat(release.number("userId")).isEqualTo(HOST);
                assertThat(release.number("amountHp")).isEqualTo(FEE);
            });
        }

        @Test
        @DisplayName("위약금 정산 성공: 호스트 일방 취소면 지갑은 취소 ID·반환액·위약금(합계 = 출연료)을 받고 작업 DONE")
        void penaltySucceeded() {
            Long appearanceId = establish();

            appearanceFacade.hostCancel(HOST, appearanceId, false, null);

            HpTask task = onlyTask(HpTaskType.PENALTY);
            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DONE);
            ReceivedRequest penalty = MockBilling.requests(Endpoint.PENALTY).getFirst();
            assertThat(penalty.requestId(Endpoint.PENALTY)).isEqualTo(task.getIdempotencyKey());
            assertThat(penalty.number("receiverUserId")).isEqualTo(GUEST);
            assertThat(penalty.number("releaseAmountHp") + penalty.number("payoutAmountHp")).isEqualTo(FEE);
        }

        @Test
        @DisplayName("지급은 됐는데 완료 기록이 빠져 다시 실행: 같은 지급 요청 ID로 다시 보내 처음 결과(replayed)를 받고 DONE")
        void payoutReplayed() {
            HpTask task = confirm();
            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DONE);
            jdbcTemplate.update("update appearances.hp_tasks set status = 'PENDING', completed_at = null where id = ?",
                    task.getId());

            hpTaskExecuteUseCase.execute(task.getId());

            assertThat(reload(task).getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(MockBilling.requests(Endpoint.PAYOUT)).hasSize(2)
                    .extracting(payout -> payout.requestId(Endpoint.PAYOUT)).containsOnly(task.getIdempotencyKey());
        }

        @Test
        @DisplayName("응답 시간 초과인데 지갑은 처리함: 작업 PENDING(UNKNOWN_RESULT) → 다음 시도에 같은 요청 ID로 다시 보내 DONE")
        void timeoutConverges() {
            MockBilling.delay(Endpoint.PAYOUT, 2_000);
            HpTask task = confirm();

            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(task.getLastError()).startsWith("UNKNOWN_RESULT: ");

            MockBilling.succeed(Endpoint.PAYOUT);
            HpTask retried = runAgain(task);

            assertThat(retried.getStatus()).isEqualTo(HpTaskStatus.DONE);
            assertThat(MockBilling.requests(Endpoint.PAYOUT)).hasSize(2)
                    .extracting(payout -> payout.requestId(Endpoint.PAYOUT)).containsOnly(task.getIdempotencyKey());
        }

        @Test
        @DisplayName("요청 한도 초과(429): 작업 PENDING, 마지막 오류에 RETRYABLE·상태를 남긴다")
        void retryable() {
            MockBilling.fail(Endpoint.RELEASE, 429, null, null);
            AppearanceRequestResDto sent = send();

            appearanceRequestFacade.reject(GUEST, sent.id(), null);

            HpTask task = onlyTask(HpTaskType.RELEASE);
            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
            assertThat(task.getLastError()).startsWith("RETRYABLE 429: ");
        }

        @Test
        @DisplayName("같은 요청 ID·다른 내용(409 IDEMPOTENCY_KEY_CONFLICT): 재시도 없이 DEAD, 확인은 성공")
        void idempotencyConflictDead() {
            MockBilling.fail(Endpoint.PAYOUT, 409, "IDEMPOTENCY_KEY_CONFLICT", "같은 지급 요청 ID로 다른 지급을 요청했습니다.");

            HpTask task = confirm();

            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DEAD);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(task.getLastError()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT: 같은 지급 요청 ID로 다른 지급을 요청했습니다.");
            AppearanceResDto appearance = appearanceFacade.getDetail(HOST, task.getAppearanceId());
            assertThat(appearance.status()).isEqualTo(AppearanceStatus.CONFIRMED);
            assertThat(appearance.hpPayoutStatus()).isEqualTo(HpTaskStatus.DEAD);
        }

        @Test
        @DisplayName("목록 밖 코드: DEAD, 마지막 오류에 원래 코드와 HTTP 상태를 남긴다")
        void unmappedDead() {
            MockBilling.fail(Endpoint.PAYOUT, 422, "NEW_WALLET_RULE", "새 지갑 규칙");

            HpTask task = confirm();

            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DEAD);
            assertThat(task.getLastError()).isEqualTo("UNMAPPED(NEW_WALLET_RULE, 422): 새 지갑 규칙");
        }

        @Test
        @DisplayName("5xx가 계속되면 10회 시도 뒤 DEAD → 지갑이 돌아온 뒤 관리자가 다시 시도하면 같은 요청 ID로 DONE")
        void deadAfterMaxAttemptsThenAdminRetry() {
            MockBilling.fail(Endpoint.PAYOUT, 500, "HOLD_STATE_INCONSISTENT", null);
            HpTask task = confirm();
            for (int attempt = 2; attempt <= 10; attempt++) {
                task = runAgain(task);
            }

            assertThat(task.getStatus()).isEqualTo(HpTaskStatus.DEAD);
            assertThat(task.getAttemptCount()).isEqualTo(10);
            assertThat(task.getLastError()).startsWith("UNKNOWN_RESULT 500 HOLD_STATE_INCONSISTENT: ");

            MockBilling.succeed(Endpoint.PAYOUT);
            HpTaskAdminResDto retried = retryAsAdmin(task.getId());

            assertThat(retried.status()).isEqualTo(HpTaskStatus.DONE);
            assertThat(MockBilling.requests(Endpoint.PAYOUT)).hasSize(11)
                    .extracting(payout -> payout.requestId(Endpoint.PAYOUT)).containsOnly(task.getIdempotencyKey());
        }

        /** 관리자 세션으로 다시 시도한다 (테스트의 나머지는 인플루언서 세션) */
        private HpTaskAdminResDto retryAsAdmin(Long taskId) {
            SecurityContext previous = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(new SecurityContextImpl(
                    new TestingAuthenticationToken(ADMIN, null, "ROLE_ADMIN")));
            try {
                return hpTaskAdminFacade.retry(ADMIN, taskId);
            } finally {
                SecurityContextHolder.setContext(previous);
            }
        }
    }
}
