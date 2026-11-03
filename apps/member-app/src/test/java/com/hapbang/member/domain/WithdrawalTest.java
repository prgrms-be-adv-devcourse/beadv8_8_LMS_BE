package com.hapbang.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.member.fixture.MemberFixture;

class WithdrawalTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 11, 0);

    @Test
    @DisplayName("시작하면 확인하는 중이고, 지갑을 닫고 회원을 저장하면 완료된다")
    void complete() {
        Withdrawal withdrawal = Withdrawal.start(MemberFixture.user());
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.CHECKING);

        withdrawal.walletClosed();
        withdrawal.complete(NOW);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(withdrawal.getCompletedAt()).isEqualTo(NOW);
        assertThat(withdrawal.getStatus().isFinished()).isTrue();
    }

    @Test
    @DisplayName("막히면 막힌 이유를 남기고 끝난다 (지갑을 닫기 전·닫아서 다시 연 뒤 모두)")
    void block() {
        Withdrawal beforeClose = Withdrawal.start(MemberFixture.user());
        beforeClose.block("[{\"code\":\"APPEARANCE_IN_PROGRESS\"}]", NOW);
        Withdrawal afterClose = Withdrawal.start(MemberFixture.user());
        afterClose.walletClosed();
        afterClose.block("[]", NOW);

        assertThat(beforeClose.getStatus()).isEqualTo(WithdrawalStatus.BLOCKED);
        assertThat(beforeClose.getBlockReasons()).contains("APPEARANCE_IN_PROGRESS");
        assertThat(afterClose.getStatus()).isEqualTo(WithdrawalStatus.BLOCKED);
    }

    @Test
    @DisplayName("결과를 모르면 복구 대상이 되고, 배치가 다시 실패하면 횟수가 오르며, 다시 열면 되돌림으로 끝난다")
    void recovery() {
        Withdrawal withdrawal = Withdrawal.start(MemberFixture.user());
        withdrawal.needRecovery("지갑 닫기 결과 불명");
        withdrawal.recoveryFailed("다시 열기 시간 초과");
        withdrawal.recoveryFailed("다시 열기 시간 초과");

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.NEEDS_RECOVERY);
        assertThat(withdrawal.getAttempts()).isEqualTo(2);
        assertThat(withdrawal.getStatus().isFinished()).isFalse();

        withdrawal.rollBack("다시 열기 성공", NOW);

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.ROLLED_BACK);
        assertThat(withdrawal.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("끝난 작업은 다른 상태로 바꿀 수 없다")
    void finishedIsFinal() {
        Withdrawal completed = Withdrawal.start(MemberFixture.user());
        completed.walletClosed();
        completed.complete(NOW);
        Withdrawal blocked = Withdrawal.start(MemberFixture.user());
        blocked.block("[]", NOW);

        assertThatThrownBy(() -> completed.rollBack("x", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> completed.needRecovery("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> blocked.walletClosed()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> blocked.rollBack("x", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("지갑을 닫지 않았으면 완료할 수 없다")
    void completeNeedsClosedWallet() {
        Withdrawal withdrawal = Withdrawal.start(MemberFixture.user());

        assertThatThrownBy(() -> withdrawal.complete(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("오류 내용은 칸 길이에 맞춰 자른다")
    void truncateError() {
        Withdrawal withdrawal = Withdrawal.start(MemberFixture.user());

        withdrawal.needRecovery("x".repeat(Withdrawal.LAST_ERROR_MAX_LENGTH + 10));

        assertThat(withdrawal.getLastError()).hasSize(Withdrawal.LAST_ERROR_MAX_LENGTH);
    }
}
