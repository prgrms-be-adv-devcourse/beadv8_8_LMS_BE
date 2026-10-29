package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.domain.HpTask;
import com.hapbang.appearance.domain.HpTaskReason;
import com.hapbang.appearance.domain.HpTaskRefType;
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
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/**
 * 낙관적 락 충돌 처리 (ISSUE.md APR-37). 스레드 타이밍에 기대지 않고, 행을 읽은 직후 다른 트랜잭션이 같은 행을 먼저 바꿔
 * 커밋한 상황을 만든다 (읽기를 가로채 별도 트랜잭션으로 버전을 올린다).
 * <ul>
 *     <li>사용자 요청(요청서·모집글 수정, 요청서 수락·거절): 반영 시점에 409 {@code CONCURRENT_UPDATE}, 아무것도 바뀌지 않는다
 *     ({@code flushOrConflict()}).</li>
 *     <li>HP 작업 결과 기록: 409를 던지지 않고 먼저 기록한 결과를 그대로 둔다 (R2, 실행은 밖으로 던지지 않음 #239).</li>
 * </ul>
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceConcurrentUpdateTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long ADMIN = 900L;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    HpTaskExecuteUseCase hpTaskExecuteUseCase;

    @Autowired
    HpTaskAdminFacade hpTaskAdminFacade;

    @MockitoSpyBean
    AppearanceRequestSupport appearanceRequestSupport;

    @MockitoSpyBean
    AppearanceRecruitSupport appearanceRecruitSupport;

    @MockitoSpyBean
    HpTaskSupport hpTaskSupport;

    @Autowired
    BillingClient billingClient;

    @Autowired
    HpTaskRepository hpTaskRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }

    private LocalDateTime now() {
        return clock.now();
    }

    /** 별도 트랜잭션(REQUIRES_NEW)으로 SQL을 실행하고 커밋한다 = "다른 요청이 먼저 바꿔 커밋함" */
    private void commitInOtherTransaction(String sql, Object... args) {
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        other.executeWithoutResult(status -> jdbcTemplate.update(sql, args));
    }

    /** 요청서를 읽은 직후, 다른 요청이 같은 요청서를 먼저 바꿔 커밋한다 (버전 +1) */
    private void otherRequestChangesRequestAfterRead() {
        doAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            commitInOtherTransaction("update appearances.requests set version = version + 1 where id = ?",
                    invocation.<Long>getArgument(0));
            return loaded;
        }).when(appearanceRequestSupport).getById(any());
    }

    private AppearanceRequestResDto send() {
        return appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(now()));
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppearanceException.class)
                .extracting("errorCode").isEqualTo(AppearanceErrorCode.CONCURRENT_UPDATE);
    }

    @Test
    @DisplayName("요청서 기본 정보 수정 중 다른 요청이 먼저 바꾸면 409 CONCURRENT_UPDATE, 제목은 그대로")
    void requestUpdate() {
        AppearanceRequestResDto sent = send();
        otherRequestChangesRequestAfterRead();

        assertConflict(() -> appearanceRequestFacade.updateBasicInfo(HOST, sent.id(), "바꾼 제목", null, null));

        assertThat(jdbcTemplate.queryForObject("select title from appearances.requests where id = ?", String.class,
                sent.id())).isEqualTo(sent.title());
    }

    @Test
    @DisplayName("요청서 수락 중 다른 요청이 먼저 바꾸면 409 CONCURRENT_UPDATE, 요청서는 REQUESTED 그대로·출연 없음")
    void requestAccept() {
        AppearanceRequestResDto sent = send();
        otherRequestChangesRequestAfterRead();

        assertConflict(() -> appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()));

        assertThat(jdbcTemplate.queryForObject("select status from appearances.requests where id = ?", String.class,
                sent.id())).isEqualTo(AppearanceRequestStatus.REQUESTED.name());
        assertThat(jdbcTemplate.queryForObject("select count(*) from appearances.appearances", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("요청서 거절 중 다른 요청이 먼저 바꾸면 409 CONCURRENT_UPDATE, 반환 작업도 기록되지 않는다 (반환 기록 통합)")
    void requestRejectRelease() {
        AppearanceRequestResDto sent = send();
        otherRequestChangesRequestAfterRead();

        assertConflict(() -> appearanceRequestFacade.reject(GUEST, sent.id(), null));

        assertThat(jdbcTemplate.queryForObject("select status from appearances.requests where id = ?", String.class,
                sent.id())).isEqualTo(AppearanceRequestStatus.REQUESTED.name());
        assertThat(hpTaskRepository.count()).isZero();
    }

    @Test
    @DisplayName("모집글 기본 정보 수정 중 다른 요청이 먼저 바꾸면 409 CONCURRENT_UPDATE, 제목은 그대로")
    void recruitUpdate() {
        var posted = appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(now()));
        doAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            commitInOtherTransaction("update appearances.recruits set version = version + 1 where id = ?",
                    invocation.<Long>getArgument(0));
            return loaded;
        }).when(appearanceRecruitSupport).getById(any());

        assertConflict(() -> appearanceRecruitFacade.updateBasicInfo(HOST, posted.id(), "바꾼 제목", null, null));

        assertThat(jdbcTemplate.queryForObject("select title from appearances.recruits where id = ?", String.class,
                posted.id())).isEqualTo(posted.title());
    }

    @Test
    @DisplayName("HP 작업 결과를 기록하려는데 다른 실행이 먼저 기록했으면 예외 없이 끝나고 먼저 기록한 결과를 둔다 (409 아님, R2)")
    void hpTaskRecordConflict() {
        AppearanceRequestResDto sent = send();
        BillingStubs.unavailable(billingClient);
        appearanceRequestFacade.reject(GUEST, sent.id(), null); // 반환 실패 → 작업 PENDING
        BillingStubs.available(billingClient);
        HpTask task = hpTaskRepository.findAll().getFirst();
        assertThat(task.getStatus()).isEqualTo(HpTaskStatus.PENDING);
        clock.advanceTo(task.getNextAttemptAt()); // 다음 시도 시각이 된다
        // 실행 시작의 첫 읽기는 그대로, 결과 기록 트랜잭션의 읽기 직후 다른 실행이 DONE으로 먼저 기록해 커밋한다
        doCallRealMethod().doAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            commitInOtherTransaction("update appearances.hp_tasks set status = 'DONE', attempt_count = 9, "
                    + "version = version + 1 where id = ?", task.getId());
            return loaded;
        }).when(hpTaskSupport).getById(task.getId());

        hpTaskExecuteUseCase.execute(task.getId());

        HpTask recorded = hpTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(recorded.getStatus()).isEqualTo(HpTaskStatus.DONE);
        assertThat(recorded.getAttemptCount()).isEqualTo(9); // 먼저 기록한 실행의 값 그대로 (덮어쓰지 않음)
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("관리자 다시 시도(#397): 격리 작업을 읽은 직후 다른 관리자가 먼저 바꿔 커밋하면 409 CONCURRENT_UPDATE, 작업·다시 시도 기록 그대로, Billing 호출 없음")
    void manualRetryConflict() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.user(ADMIN, Role.ADMIN, UserStatus.ACTIVE));
        HpTask released = hpTaskRepository.saveAndFlush(HpTask.release(HpTaskRefType.APPEARANCE_REQUEST, 11L, HOST,
                30_000L, HpTaskReason.REQUEST_REJECTED,
                HpTask.requestId(HpTaskRefType.APPEARANCE_REQUEST, 11L, HpTaskType.RELEASE, 1), now()));
        BillingStubs.reject(billingClient, Rejection.HOLD_NOT_FOUND);
        hpTaskExecuteUseCase.execute(released.getId()); // Billing 거절 → 작업 DEAD
        BillingStubs.available(billingClient);
        HpTask dead = hpTaskRepository.findById(released.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(HpTaskStatus.DEAD);
        doAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            commitInOtherTransaction("update appearances.hp_tasks set version = version + 1 where id = ?",
                    dead.getId());
            return loaded;
        }).when(hpTaskSupport).getForAdmin(dead.getId());

        assertThatThrownBy(() -> hpTaskAdminFacade.retry(ADMIN, dead.getId()))
                .isInstanceOfSatisfying(AppearanceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.CONCURRENT_UPDATE));

        HpTask after = hpTaskRepository.findById(dead.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(HpTaskStatus.DEAD);
        assertThat(after.getManualRetryCount()).isZero();
        assertThat(BillingCalls.none(billingClient)).isTrue();
    }
}
