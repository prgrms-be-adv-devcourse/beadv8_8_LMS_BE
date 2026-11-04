package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.MemberErrorCode;
import com.hapbang.member.domain.MemberException;
import com.hapbang.member.domain.User;
import com.hapbang.member.domain.Withdrawal;
import com.hapbang.member.domain.WithdrawalBlockedException;
import com.hapbang.member.domain.WithdrawalStatus;
import com.hapbang.member.fixture.MemberFixture;
import com.hapbang.member.out.RefreshTokenStore;
import com.hapbang.member.out.UserRepository;
import com.hapbang.member.out.WalletCloseResult;
import com.hapbang.member.out.WalletWithdrawalGate;
import com.hapbang.member.out.WithdrawalBlockSource;
import com.hapbang.member.out.WithdrawalCallException;
import com.hapbang.member.out.WithdrawalRepository;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;
import com.hapbang.shared.member.event.UserModifiedEvent;

/**
 * 회원 탈퇴 오케스트레이터 (#593). 출연·강의·지갑은 가짜 구현으로 두고, 실제 PostgreSQL로 탈퇴 작업 기록·회원 상태·보상을 확인한다
 */
@MemberIntegrationTest
@TestPropertySource(properties = MemberWithdrawalUseCase.ENABLED + "=true")
@Import(MemberWithdrawalUseCaseTest.FakeParticipants.class)
@RecordApplicationEvents
class MemberWithdrawalUseCaseTest {

    private static final WithdrawalBlockReasonDto APPEARANCE_REASON =
            WithdrawalBlockReasonDto.of("APPEARANCE_IN_PROGRESS", "진행 중인 출연 1건이 있어요");
    private static final WithdrawalBlockReasonDto LECTURE_REASON =
            WithdrawalBlockReasonDto.of("LECTURE_ORDER_HOLDING", "구매한 강의의 보류가 끝난 뒤 탈퇴할 수 있어요");
    private static final WithdrawalBlockReasonDto BILLING_REASON =
            WithdrawalBlockReasonDto.of("BILLING_CHARGE_BALANCE_REMAINING", "충전 HP가 남아 있어요");

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeParticipants {

        @Bean
        FakeBlockSource appearanceBlockSource() {
            return new FakeBlockSource("appearance");
        }

        @Bean
        FakeBlockSource lectureBlockSource() {
            return new FakeBlockSource("lecture");
        }

        @Bean
        FakeWalletGate fakeWalletGate() {
            return new FakeWalletGate();
        }
    }

    @Autowired
    MemberWithdrawalUseCase memberWithdrawalUseCase;

    @Autowired
    UserRepository userRepository;

    @Autowired
    WithdrawalRepository withdrawalRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    FakeBlockSource appearanceBlockSource;

    @Autowired
    FakeBlockSource lectureBlockSource;

    @Autowired
    FakeWalletGate walletGate;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    RefreshTokenStore refreshTokenStore;

    @BeforeEach
    void reset() {
        appearanceBlockSource.reset();
        lectureBlockSource.reset();
        walletGate.reset();
    }

    @Test
    @DisplayName("막힌 이유가 없으면 지갑을 닫고 회원을 탈퇴시키고, 이벤트를 남기고 refresh token을 폐기한다")
    void complete() {
        User user = userRepository.save(MemberFixture.user());

        memberWithdrawalUseCase.withdraw(user.getId());

        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(saved.getDeletedAt()).isNotNull();
        Withdrawal withdrawal = onlyWithdrawalOf(user);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(walletGate.closed).containsExactly(withdrawal.getId());
        assertThat(walletGate.reopened).isEmpty();
        assertThat(events.stream(UserModifiedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.user().status()).isEqualTo(UserStatus.WITHDRAWN));
        verify(refreshTokenStore).revokeAll(user.getId());
    }

    @Test
    @DisplayName("출연이 막으면 닫힌 지갑을 다시 열고(보상) 막힌 이유를 남긴 뒤 409")
    void blockedByAppearance() {
        User user = userRepository.save(MemberFixture.user());
        appearanceBlockSource.reasons = List.of(APPEARANCE_REASON);

        assertThatThrownBy(() -> memberWithdrawalUseCase.withdraw(user.getId()))
                .isInstanceOfSatisfying(WithdrawalBlockedException.class, e -> assertThat(e.getReasons())
                        .containsExactly(APPEARANCE_REASON));

        Withdrawal withdrawal = onlyWithdrawalOf(user);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.BLOCKED);
        assertThat(withdrawal.getBlockReasons()).contains("APPEARANCE_IN_PROGRESS");
        assertThat(walletGate.reopened).containsExactly(withdrawal.getId());
        assertActive(user);
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("여러 앱이 막으면 이유를 한 번에 모으고, 지갑이 막아 닫지 않았으면 다시 열지 않는다")
    void blockedByMany() {
        User user = userRepository.save(MemberFixture.user());
        lectureBlockSource.reasons = List.of(LECTURE_REASON);
        walletGate.closeResult = WalletCloseResult.blocked(List.of(BILLING_REASON));

        assertThatThrownBy(() -> memberWithdrawalUseCase.withdraw(user.getId()))
                .isInstanceOfSatisfying(WithdrawalBlockedException.class, e -> assertThat(e.getReasons())
                        .containsExactly(LECTURE_REASON, BILLING_REASON));

        assertThat(onlyWithdrawalOf(user).getStatus()).isEqualTo(WithdrawalStatus.BLOCKED);
        assertThat(walletGate.reopened).isEmpty();
        assertActive(user);
    }

    @Test
    @DisplayName("막힌 이유를 확인하지 못하면 지갑을 부르지 않고 되돌림으로 끝낸 뒤 503")
    void blockSourceUnavailable() {
        User user = userRepository.save(MemberFixture.user());
        lectureBlockSource.failure = new WithdrawalCallException(WithdrawalCallException.Kind.UNKNOWN_RESULT,
                "강의 응답 없음", null);

        assertUnavailable(user);

        assertThat(onlyWithdrawalOf(user).getStatus()).isEqualTo(WithdrawalStatus.ROLLED_BACK);
        assertThat(walletGate.closed).isEmpty();
        assertActive(user);
    }

    @Test
    @DisplayName("지갑이 닫기를 처리하지 않은 것이 확실하면 다시 열지 않고 되돌림으로 끝낸 뒤 503")
    void walletNotProcessed() {
        User user = userRepository.save(MemberFixture.user());
        walletGate.closeFailure = new WithdrawalCallException(WithdrawalCallException.Kind.NOT_PROCESSED,
                "연결 거부", null);

        assertUnavailable(user);

        assertThat(onlyWithdrawalOf(user).getStatus()).isEqualTo(WithdrawalStatus.ROLLED_BACK);
        assertThat(walletGate.reopened).isEmpty();
        assertActive(user);
    }

    @Test
    @DisplayName("지갑 닫기 결과를 모르면 바로 다시 열고, 다시 열면 되돌림으로 끝낸 뒤 503")
    void walletUnknownThenReopened() {
        User user = userRepository.save(MemberFixture.user());
        walletGate.closeFailure = new WithdrawalCallException(WithdrawalCallException.Kind.UNKNOWN_RESULT,
                "응답 시간 초과", null);

        assertUnavailable(user);

        Withdrawal withdrawal = onlyWithdrawalOf(user);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.ROLLED_BACK);
        assertThat(walletGate.reopened).containsExactly(withdrawal.getId());
        assertActive(user);
    }

    @Test
    @DisplayName("지갑 닫기도 다시 열기도 결과를 모르면 복구 대상으로 남기고 503 (배치가 이어서 연다)")
    void walletUnknownAndReopenUnknown() {
        User user = userRepository.save(MemberFixture.user());
        walletGate.closeFailure = new WithdrawalCallException(WithdrawalCallException.Kind.UNKNOWN_RESULT,
                "응답 시간 초과", null);
        walletGate.reopenFailure = new WithdrawalCallException(WithdrawalCallException.Kind.UNKNOWN_RESULT,
                "응답 시간 초과", null);

        assertUnavailable(user);

        Withdrawal withdrawal = onlyWithdrawalOf(user);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.NEEDS_RECOVERY);
        assertThat(withdrawal.getAttempts()).isEqualTo(1);
        assertThat(withdrawal.getLastError()).contains("지갑 다시 열기 실패");
        assertActive(user);
    }

    @Test
    @DisplayName("지갑을 닫은 뒤 회원 탈퇴 저장이 실패하면 지갑을 다시 열고(보상) 되돌림으로 끝낸 뒤 503")
    void completeFailsThenCompensate() {
        User user = userRepository.save(MemberFixture.user());
        // 지갑을 닫는 사이 회원이 정상 상태가 아니게 됨 (탈퇴 저장이 실패하는 상황)
        walletGate.onClose = () -> transactionTemplate.executeWithoutResult(status -> {
            User changed = userRepository.findById(user.getId()).orElseThrow();
            ReflectionTestUtils.setField(changed, "status", UserStatus.SUSPENDED);
        });

        assertUnavailable(user);

        Withdrawal withdrawal = onlyWithdrawalOf(user);
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.ROLLED_BACK);
        assertThat(withdrawal.getLastError()).contains("회원 탈퇴 저장 실패");
        assertThat(walletGate.reopened).containsExactly(withdrawal.getId());
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
        verify(refreshTokenStore, never()).revokeAll(user.getId());
    }

    @Test
    @DisplayName("진행 중인 탈퇴가 있으면 409 WITHDRAWAL_IN_PROGRESS")
    void inProgress() {
        User user = userRepository.save(MemberFixture.user());
        withdrawalRepository.saveAndFlush(Withdrawal.start(user));

        assertThatThrownBy(() -> memberWithdrawalUseCase.withdraw(user.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.WITHDRAWAL_IN_PROGRESS);
        assertThat(walletGate.closed).isEmpty();
    }

    @Test
    @DisplayName("이미 탈퇴한 회원이 다시 보내면 아무것도 하지 않고 성공한다 (멱등)")
    void alreadyWithdrawn() {
        User user = MemberFixture.user();
        ReflectionTestUtils.setField(user, "status", UserStatus.WITHDRAWN);
        User withdrawn = userRepository.save(user);

        memberWithdrawalUseCase.withdraw(withdrawn.getId());

        assertThat(withdrawalsOf(withdrawn)).isEmpty();
        assertThat(walletGate.closed).isEmpty();
    }

    @Test
    @DisplayName("정지 회원은 탈퇴 작업을 만들지 않고 MEMBER_NOT_FOUND")
    void suspended() {
        User user = MemberFixture.user();
        ReflectionTestUtils.setField(user, "status", UserStatus.SUSPENDED);
        User suspended = userRepository.save(user);

        assertThatThrownBy(() -> memberWithdrawalUseCase.withdraw(suspended.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
        assertThat(withdrawalsOf(suspended)).isEmpty();
    }

    @Test
    @DisplayName("2분 넘게 멈춘 작업이 있으면 다시 누를 때 지갑을 다시 열어 정리하고 새로 탈퇴한다")
    void staleRecoveredOnRetry() {
        User user = userRepository.save(MemberFixture.user());
        Long stuckId = withdrawalRepository.saveAndFlush(Withdrawal.start(user)).getId();
        makeStale(stuckId);

        memberWithdrawalUseCase.withdraw(user.getId());

        assertThat(walletGate.reopened).containsExactly(stuckId);
        assertThat(withdrawalsOf(user)).extracting(Withdrawal::getStatus)
                .containsExactlyInAnyOrder(WithdrawalStatus.ROLLED_BACK, WithdrawalStatus.COMPLETED);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("복구가 필요한 작업이 있는데 다시 누를 때도 지갑을 다시 열지 못하면 503이고 실패 횟수가 오른다")
    void recoveryFailsOnRetry() {
        User user = userRepository.save(MemberFixture.user());
        Withdrawal stuck = Withdrawal.start(user);
        stuck.needRecovery("지갑 닫기 결과 불명");
        Long stuckId = withdrawalRepository.saveAndFlush(stuck).getId();
        walletGate.reopenFailure = new WithdrawalCallException(WithdrawalCallException.Kind.UNKNOWN_RESULT,
                "응답 시간 초과", null);

        assertUnavailable(user);

        Withdrawal saved = onlyWithdrawalOf(user);
        assertThat(saved.getId()).isEqualTo(stuckId);
        assertThat(saved.getStatus()).isEqualTo(WithdrawalStatus.NEEDS_RECOVERY);
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertActive(user);
    }

    @Test
    @DisplayName("배치는 복구가 필요한 작업과 2분 넘게 멈춘 작업만 정리하고, 아직 처리 중일 수 있는 작업과 끝난 작업은 두지 않는다")
    void recoverStuck() {
        User recoveryUser = userRepository.save(MemberFixture.user());
        User staleUser = userRepository.save(MemberFixture.user());
        User freshUser = userRepository.save(MemberFixture.user());
        User finishedUser = userRepository.save(MemberFixture.user());
        Withdrawal needsRecovery = Withdrawal.start(recoveryUser);
        needsRecovery.needRecovery("지갑 닫기 결과 불명");
        Long recoveryId = withdrawalRepository.saveAndFlush(needsRecovery).getId();
        Long staleId = withdrawalRepository.saveAndFlush(Withdrawal.start(staleUser)).getId();
        makeStale(staleId);
        Long freshId = withdrawalRepository.saveAndFlush(Withdrawal.start(freshUser)).getId();
        Withdrawal finished = Withdrawal.start(finishedUser);
        finished.block("[]", LocalDateTime.now());
        Long finishedId = withdrawalRepository.saveAndFlush(finished).getId();
        makeStale(finishedId);

        int recovered = memberWithdrawalUseCase.recoverStuck(100);

        assertThat(recovered).isEqualTo(2);
        assertThat(walletGate.reopened).containsExactlyInAnyOrder(recoveryId, staleId);
        assertThat(statusOf(recoveryId)).isEqualTo(WithdrawalStatus.ROLLED_BACK);
        assertThat(statusOf(staleId)).isEqualTo(WithdrawalStatus.ROLLED_BACK);
        assertThat(statusOf(freshId)).isEqualTo(WithdrawalStatus.CHECKING);
        assertThat(statusOf(finishedId)).isEqualTo(WithdrawalStatus.BLOCKED);
    }

    @Test
    @DisplayName("배치가 지갑을 다시 열지 못하면 실패 횟수만 올리고 다음에 다시 시도한다")
    void recoverStuckFails() {
        User user = userRepository.save(MemberFixture.user());
        Withdrawal needsRecovery = Withdrawal.start(user);
        needsRecovery.needRecovery("지갑 닫기 결과 불명");
        Long id = withdrawalRepository.saveAndFlush(needsRecovery).getId();
        walletGate.reopenFailure = new WithdrawalCallException(WithdrawalCallException.Kind.UNKNOWN_RESULT,
                "응답 시간 초과", null);

        assertThat(memberWithdrawalUseCase.recoverStuck(100)).isZero();
        assertThat(memberWithdrawalUseCase.recoverStuck(100)).isZero();

        Withdrawal saved = onlyWithdrawalOf(user);
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getStatus()).isEqualTo(WithdrawalStatus.NEEDS_RECOVERY);
        assertThat(saved.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("복구하면 탈퇴 때 닫은 지갑을 다시 열고 회원을 정상으로 되돌리며 이벤트를 남긴다")
    void restore() {
        User user = userRepository.save(MemberFixture.user());
        memberWithdrawalUseCase.withdraw(user.getId());
        Long completedId = onlyWithdrawalOf(user).getId();
        events.clear();

        User restored = memberWithdrawalUseCase.restore(user.getId());

        assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertActive(user);
        assertThat(walletGate.reopened).containsExactly(completedId);
        assertThat(events.stream(UserModifiedEvent.class)).singleElement()
                .satisfies(event -> assertThat(event.user().status()).isEqualTo(UserStatus.ACTIVE));
    }

    @Test
    @DisplayName("복구하려는데 지갑을 다시 열지 못하면 복구하지 않고 503 RESTORE_UNAVAILABLE")
    void restoreWalletUnavailable() {
        User user = userRepository.save(MemberFixture.user());
        memberWithdrawalUseCase.withdraw(user.getId());
        walletGate.reopenFailure = new WithdrawalCallException(WithdrawalCallException.Kind.UNKNOWN_RESULT,
                "응답 시간 초과", null);

        assertThatThrownBy(() -> memberWithdrawalUseCase.restore(user.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.RESTORE_UNAVAILABLE);
        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(saved.getRestoreFailedAt()).isNotNull();
    }

    @Test
    @DisplayName("기한 안에 지갑 장애로 복구가 실패했으면 기한이 지나도 7일 안에는 복구할 수 있다 (#594)")
    void restoreWithinGrace() {
        User user = userRepository.save(MemberFixture.user());
        memberWithdrawalUseCase.withdraw(user.getId());
        jdbcTemplate.update("""
                update members.users set deleted_at = now() - interval '31 days', restore_failed_at = now() - interval '2 days'
                where id = ?""", user.getId());

        User restored = memberWithdrawalUseCase.restore(user.getId());

        assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getRestoreFailedAt()).isNull();
    }

    @Test
    @DisplayName("유예 7일도 지났으면 지갑을 부르지 않고 LOGIN_NOT_ALLOWED (#594)")
    void restoreAfterGrace() {
        User user = userRepository.save(MemberFixture.user());
        memberWithdrawalUseCase.withdraw(user.getId());
        jdbcTemplate.update("""
                update members.users set deleted_at = now() - interval '38 days', restore_failed_at = now() - interval '9 days'
                where id = ?""", user.getId());

        assertThatThrownBy(() -> memberWithdrawalUseCase.restore(user.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.LOGIN_NOT_ALLOWED);
        assertThat(walletGate.reopened).isEmpty();
    }

    @Test
    @DisplayName("복구 기한이 지났으면 지갑을 부르지 않고 LOGIN_NOT_ALLOWED")
    void restoreExpired() {
        User user = userRepository.save(MemberFixture.user());
        memberWithdrawalUseCase.withdraw(user.getId());
        jdbcTemplate.update("update members.users set deleted_at = now() - interval '31 days' where id = ?", user.getId());

        assertThatThrownBy(() -> memberWithdrawalUseCase.restore(user.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.LOGIN_NOT_ALLOWED);
        assertThat(walletGate.reopened).isEmpty();
    }

    @Test
    @DisplayName("지갑은 다시 열었는데 회원 복구 저장이 실패하면 회원은 탈퇴 상태 그대로이고, 다시 시도할 수 있다")
    void restoreSaveFailsAfterReopen() {
        User user = userRepository.save(MemberFixture.user());
        memberWithdrawalUseCase.withdraw(user.getId());
        events.clear();
        // 지갑을 여는 사이 복구 기한이 지나 회원 복구 저장이 실패하는 상황
        walletGate.onReopen = () -> jdbcTemplate.update(
                "update members.users set deleted_at = now() - interval '31 days' where id = ?", user.getId());

        assertThatThrownBy(() -> memberWithdrawalUseCase.restore(user.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.LOGIN_NOT_ALLOWED);
        assertThat(walletGate.reopened).hasSize(1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("이미 정상 회원이면 아무것도 하지 않고 그대로 돌려준다 (두 번 누름)")
    void restoreAlreadyActive() {
        User user = userRepository.save(MemberFixture.user());

        User result = memberWithdrawalUseCase.restore(user.getId());

        assertThat(result.getId()).isEqualTo(user.getId());
        assertThat(walletGate.reopened).isEmpty();
        assertThat(events.stream(UserModifiedEvent.class)).isEmpty();
    }

    /** 처리하던 요청이 멈춘 것처럼 마지막 변경 시각을 5분 전으로 돌린다 */
    private void makeStale(Long withdrawalId) {
        jdbcTemplate.update("update members.withdrawal set updated_at = now() - interval '5 minutes' where id = ?",
                withdrawalId);
    }

    private WithdrawalStatus statusOf(Long withdrawalId) {
        return withdrawalRepository.findById(withdrawalId).orElseThrow().getStatus();
    }

    private void assertUnavailable(User user) {
        assertThatThrownBy(() -> memberWithdrawalUseCase.withdraw(user.getId()))
                .isInstanceOf(MemberException.class)
                .extracting("errorCode").isEqualTo(MemberErrorCode.WITHDRAWAL_UNAVAILABLE);
    }

    private void assertActive(User user) {
        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getDeletedAt()).isNull();
    }

    private Withdrawal onlyWithdrawalOf(User user) {
        List<Withdrawal> withdrawals = withdrawalsOf(user);
        assertThat(withdrawals).hasSize(1);
        return withdrawals.getFirst();
    }

    private List<Withdrawal> withdrawalsOf(User user) {
        return transactionTemplate.execute(status -> withdrawalRepository.findAll().stream()
                .filter(withdrawal -> withdrawal.getUser().getId().equals(user.getId()))
                .toList());
    }

    /** 출연·강의 대역. 이유나 실패를 테스트마다 정한다 */
    static class FakeBlockSource implements WithdrawalBlockSource {

        private final String name;
        List<WithdrawalBlockReasonDto> reasons = List.of();
        RuntimeException failure;

        FakeBlockSource(String name) {
            this.name = name;
        }

        void reset() {
            reasons = List.of();
            failure = null;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public WithdrawalBlocksDto blocks(long userId) {
            if (failure != null) {
                throw failure;
            }
            return new WithdrawalBlocksDto(reasons);
        }
    }

    /** 지갑 대역. 닫기 결과·실패와 부른 작업 ID를 기록한다 */
    static class FakeWalletGate implements WalletWithdrawalGate {

        WalletCloseResult closeResult;
        RuntimeException closeFailure;
        RuntimeException reopenFailure;
        Runnable onClose;
        Runnable onReopen;
        final List<Long> closed = new ArrayList<>();
        final List<Long> reopened = new ArrayList<>();

        FakeWalletGate() {
            reset();
        }

        void reset() {
            closeResult = WalletCloseResult.closedWallet();
            closeFailure = null;
            reopenFailure = null;
            onClose = () -> {
            };
            onReopen = () -> {
            };
            closed.clear();
            reopened.clear();
        }

        @Override
        public WalletCloseResult close(long withdrawalId, long userId) {
            onClose.run();
            if (closeFailure != null) {
                throw closeFailure;
            }
            if (closeResult.closed()) {
                closed.add(withdrawalId);
            }
            return closeResult;
        }

        @Override
        public void reopen(long withdrawalId, long userId) {
            onReopen.run();
            if (reopenFailure != null) {
                throw reopenFailure;
            }
            reopened.add(withdrawalId);
        }
    }
}
