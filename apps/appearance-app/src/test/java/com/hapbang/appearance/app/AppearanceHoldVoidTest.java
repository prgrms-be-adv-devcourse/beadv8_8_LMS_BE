package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

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

import com.hapbang.appearance.config.AppearanceBatchProperties;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceRecruit;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRecruitRepository;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.out.billing.BillingClient;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.BillingStubs;
import com.hapbang.appearance.support.BillingStubs.Rejection;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.HoldVoidOutcome;
import com.hapbang.shared.billing.dto.HoldVoidCommand;

/**
 * 보류 결과를 확정하지 못한 요청서·모집글을 보류 무효화로 끝내기 (#536).
 * <ul>
 *     <li>보류 재확인 배치는 만든 지 포기 기준(1시간)이 지나기 전에는 지금처럼 보류를 다시 보내고, 지난 뒤에는 보류 대신 무효화를 보낸다.</li>
 *     <li>지갑은 Mockito 대역이다. 무효화 결과(반환함·보류 없음·이미 끝남)와 거절·무응답을 대역으로 만든다. 실제 잔액 복구는 지갑 쪽 책임이고,
 *     여기서는 출연이 보낸 요청과 출연 상태만 본다. 실제 HTTP 요청 모양은 {@code AppearanceBillingHttpTest}가 본다.</li>
 * </ul>
 */
@AppearanceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@WithMockUser(roles = "INFLUENCER")
class AppearanceHoldVoidTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;

    @Autowired
    AppearanceHoldRecheckUseCase appearanceHoldRecheckUseCase;

    @Autowired
    AppearanceRequestExpireUseCase appearanceRequestExpireUseCase;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    AppearanceRecruitRepository appearanceRecruitRepository;

    @Autowired
    AppearanceBatchProperties batchProperties;

    @Autowired
    BillingClient billingClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, GUEST2)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 준비

    /** 지갑 무응답으로 보내 HOLD_PENDING 요청서를 만든다. 그 뒤 지갑 대역은 기본(모두 성공)으로 돌아가고 호출 기록도 지운다 */
    private Long requestHoldPending(Long guestId) {
        BillingStubs.unavailable(billingClient);
        assertThatThrownBy(() -> appearanceRequestFacade.create(HOST, guestId,
                AppearanceRequestFixture.content(clock.now())))
                .isInstanceOfSatisfying(AppearanceException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.BILLING_UNAVAILABLE));
        BillingStubs.available(billingClient);
        Long id = null;
        for (AppearanceRequest request : appearanceRequestRepository.findAll()) {
            if (request.getGuestId().equals(guestId) && request.getStatus() == AppearanceRequestStatus.HOLD_PENDING) {
                id = request.getId();
            }
        }
        assertThat(id).isNotNull();
        return id;
    }

    /** 지갑 무응답으로 올려 HOLD_PENDING 모집글을 만든다 */
    private Long recruitHoldPending() {
        BillingStubs.unavailable(billingClient);
        assertThatThrownBy(() -> appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(clock.now())))
                .isInstanceOfSatisfying(AppearanceException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.BILLING_UNAVAILABLE));
        BillingStubs.available(billingClient);
        List<AppearanceRecruit> all = appearanceRecruitRepository.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst().getId();
    }

    private AppearanceRequestStatus requestStatus(Long id) {
        return appearanceRequestRepository.findById(id).orElseThrow().getStatus();
    }

    private AppearanceRecruitStatus recruitStatus(Long id) {
        return appearanceRecruitRepository.findById(id).orElseThrow().getStatus();
    }

    /** 만든 시각에서 포기 기준(1시간)이 막 지났다 */
    private void voidAfterPassed() {
        clock.advance(batchProperties.holdVoidAfter());
    }

    private static HoldVoidCommand requestVoid(Long requestId) {
        return new HoldVoidCommand(HOST, BillingReferenceType.APPEARANCE_REQUEST, requestId);
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("포기 기준 전")
    class BeforeVoidAfter {

        @Test
        @DisplayName("만든 지 59분이면 지금처럼 보류를 다시 보내 REQUESTED가 되고, 무효화는 보내지 않는다 (정상 지연 건을 실패로 만들지 않음)")
        void holdAgain() {
            Long requestId = requestHoldPending(GUEST);
            clock.advance(Duration.ofMinutes(59));

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isEqualTo(1);

            assertThat(requestStatus(requestId)).isEqualTo(AppearanceRequestStatus.REQUESTED);
            verify(billingClient).hold(any());
            verify(billingClient, never()).voidHold(any());
        }

        @Test
        @DisplayName("다시 보낸 보류가 HOLD_VOIDED로 거절되면(무효화 뒤 늦게 도착한 보류) HOLD_FAILED, 상태 충돌 없음")
        void lateHoldAfterVoid() {
            Long requestId = requestHoldPending(GUEST);
            clock.advance(Duration.ofMinutes(2));
            BillingStubs.reject(billingClient, Rejection.HOLD_VOIDED);

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isEqualTo(1);

            assertThat(requestStatus(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
        }

        @Test
        @DisplayName("보내기 요청의 보류가 HOLD_VOIDED로 거절되면 500 HP_HOLD_FAILED, 요청서는 HOLD_FAILED")
        void createRejectedAsVoided() {
            BillingStubs.reject(billingClient, Rejection.HOLD_VOIDED);

            assertThatThrownBy(() -> appearanceRequestFacade.create(HOST, GUEST,
                    AppearanceRequestFixture.content(clock.now())))
                    .isInstanceOfSatisfying(AppearanceException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(AppearanceErrorCode.HP_HOLD_FAILED));
            assertThat(appearanceRequestRepository.findAll()).singleElement()
                    .extracting(AppearanceRequest::getStatus).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("포기 기준 이후 (보류 무효화)")
    class AfterVoidAfter {

        @Test
        @DisplayName("요청서: 만든 지 1시간이면 보류 대신 무효화(호스트·APPEARANCE_REQUEST·요청서 ID)를 보내고, VOIDED → HOLD_FAILED")
        void requestVoided() {
            Long requestId = requestHoldPending(GUEST);
            voidAfterPassed();

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isEqualTo(1);

            assertThat(requestStatus(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
            verify(billingClient).voidHold(requestVoid(requestId));
            verify(billingClient, never()).hold(any());
        }

        @Test
        @DisplayName("모집글: 지갑에 보류가 실제로 있었으면 RELEASED(반환액) → HOLD_FAILED, 무효화는 호스트·APPEARANCE_RECRUIT·모집글 ID")
        void recruitReleased() {
            Long recruitId = recruitHoldPending();
            voidAfterPassed();
            BillingStubs.voidHoldResult(billingClient, HoldVoidOutcome.RELEASED, AppearanceRecruitFixture.TOTAL_FEE, false);

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isEqualTo(1);

            assertThat(recruitStatus(recruitId)).isEqualTo(AppearanceRecruitStatus.HOLD_FAILED);
            verify(billingClient).voidHold(new HoldVoidCommand(HOST, BillingReferenceType.APPEARANCE_RECRUIT, recruitId));
            verify(billingClient, never()).hold(any());
        }

        @Test
        @DisplayName("ALREADY_CLOSED(보류가 이미 끝남)도 HOLD_FAILED. 출연은 HOLD_PENDING 동안 반환·지급을 보내지 않아 예상 밖 상태라 WARN 로그")
        void alreadyClosed(CapturedOutput output) {
            Long requestId = requestHoldPending(GUEST);
            voidAfterPassed();
            BillingStubs.voidHoldResult(billingClient, HoldVoidOutcome.ALREADY_CLOSED, 0, false);

            appearanceHoldRecheckUseCase.recheckDue();

            assertThat(requestStatus(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
            assertThat(output.getOut().lines()
                    .filter(line -> line.contains("보류가 이미 끝나 있었다(예상 밖)") && line.contains("id=" + requestId)))
                    .singleElement().asString().contains(" WARN ");
        }

        @Test
        @DisplayName("VOIDED(보류 없었음)는 정리만 된 것이라 INFO 로그")
        void voidedIsInfo(CapturedOutput output) {
            Long requestId = requestHoldPending(GUEST);
            voidAfterPassed();

            appearanceHoldRecheckUseCase.recheckDue();

            assertThat(output.getOut().lines()
                    .filter(line -> line.contains("HP 보류 무효화: 요청서을 HOLD_FAILED로 둔다") && line.contains("id=" + requestId)))
                    .singleElement().asString().contains(" INFO ");
        }

        @Test
        @DisplayName("무효화 결과를 모르면 HOLD_PENDING 그대로 → 다음 회차에 다시 무효화(보류는 다시 보내지 않음) → 처음 결과(replayed)로 HOLD_FAILED")
        void unknownThenReplayed() {
            Long requestId = requestHoldPending(GUEST);
            voidAfterPassed();
            BillingStubs.unavailable(billingClient);

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isZero();
            assertThat(requestStatus(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);

            BillingStubs.available(billingClient);
            BillingStubs.voidHoldResult(billingClient, HoldVoidOutcome.VOIDED, 0, true);
            clock.advance(Duration.ofMinutes(1));
            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isEqualTo(1);

            assertThat(requestStatus(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
            verify(billingClient).voidHold(requestVoid(requestId));
            verify(billingClient, never()).hold(any());
        }

        @Test
        @DisplayName("지갑이 무효화를 거절하면(HOLD_OWNER_MISMATCH) 경고만 남기고 HOLD_PENDING 그대로, 다음 회차에도 무효화를 다시 보낸다")
        void voidRejected() {
            Long requestId = requestHoldPending(GUEST);
            voidAfterPassed();
            BillingStubs.reject(billingClient, Rejection.HOLD_OWNER_MISMATCH);

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isZero();
            assertThat(requestStatus(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_PENDING);

            clock.advance(Duration.ofMinutes(1));
            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isZero();
            verify(billingClient, times(2)).voidHold(requestVoid(requestId));
            verify(billingClient, never()).hold(any());
        }

        @Test
        @DisplayName("수락 기한이 이미 지난 요청서도 같은 기준으로 무효화해 HOLD_FAILED, 만료 배치는 건드리지 않는다")
        void acceptDeadlinePassed() {
            Long requestId = requestHoldPending(GUEST);
            clock.advanceTo(appearanceRequestRepository.findById(requestId).orElseThrow().getAcceptDeadline()
                    .plusMinutes(1));

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isEqualTo(1);
            assertThat(appearanceRequestExpireUseCase.expireDue()).isZero();

            assertThat(requestStatus(requestId)).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
            verify(billingClient).voidHold(requestVoid(requestId));
        }

        @Test
        @DisplayName("무효화로 HOLD_FAILED가 되면 진행 중 요청이 아니어서, 같은 게스트에게 새 요청서를 보낼 수 있다")
        void canSendAgain() {
            requestHoldPending(GUEST);
            voidAfterPassed();
            appearanceHoldRecheckUseCase.recheckDue();

            assertThat(appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(clock.now()))
                    .status()).isEqualTo(AppearanceRequestStatus.REQUESTED);
        }

        @Test
        @DisplayName("한 회차에서 포기 기준이 지난 건은 무효화, 아직인 건은 보류를 다시 보낸다")
        void mixed() {
            Long oldRequest = requestHoldPending(GUEST);
            clock.advance(Duration.ofMinutes(30));
            Long youngRequest = requestHoldPending(GUEST2);
            // 만든 시각(JPA Auditing)은 테스트 시계가 아니라 실제 시각이라, 30분 뒤에 만든 요청서로 맞춘다
            jdbcTemplate.update("update appearances.requests set created_at = ? where id = ?", clock.now(), youngRequest);
            clock.advance(Duration.ofMinutes(30));

            assertThat(appearanceHoldRecheckUseCase.recheckDue()).isEqualTo(2);

            assertThat(requestStatus(oldRequest)).isEqualTo(AppearanceRequestStatus.HOLD_FAILED);
            assertThat(requestStatus(youngRequest)).isEqualTo(AppearanceRequestStatus.REQUESTED);
            verify(billingClient).voidHold(requestVoid(oldRequest));
            verify(billingClient, never()).voidHold(requestVoid(youngRequest));
        }
    }
}
