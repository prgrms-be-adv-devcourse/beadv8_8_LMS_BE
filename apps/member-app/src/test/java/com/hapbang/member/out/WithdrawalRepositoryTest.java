package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.member.MemberIntegrationTest;
import com.hapbang.member.domain.User;
import com.hapbang.member.domain.Withdrawal;
import com.hapbang.member.domain.WithdrawalStatus;
import com.hapbang.member.fixture.MemberFixture;

/** 탈퇴 작업 기록 저장 (#593). 실제 PostgreSQL로 JSONB 저장과 진행 중 작업 중복 거절을 확인한다 */
@MemberIntegrationTest
class WithdrawalRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 11, 0);

    @Autowired
    WithdrawalRepository withdrawalRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("막힌 이유 JSON을 저장하고 다시 읽는다")
    void blockReasons() {
        User user = userRepository.save(MemberFixture.user());
        Withdrawal withdrawal = Withdrawal.start(user);
        withdrawal.block("[{\"code\":\"APPEARANCE_IN_PROGRESS\",\"message\":\"진행 중인 출연 1건이 있어요\"}]", NOW);

        Long id = withdrawalRepository.saveAndFlush(withdrawal).getId();

        assertThat(withdrawalRepository.findById(id)).hasValueSatisfying(saved -> {
            assertThat(saved.getStatus()).isEqualTo(WithdrawalStatus.BLOCKED);
            assertThat(saved.getBlockReasons()).contains("APPEARANCE_IN_PROGRESS").contains("진행 중인 출연 1건이 있어요");
            assertThat(saved.getCompletedAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("한 회원에 진행 중인 작업이 있으면 새 작업을 저장할 수 없다")
    void oneInProgressPerUser() {
        User user = userRepository.save(MemberFixture.user());
        withdrawalRepository.saveAndFlush(Withdrawal.start(user));

        assertThatThrownBy(() -> withdrawalRepository.saveAndFlush(Withdrawal.start(user)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_withdrawal_user_in_progress");
    }

    @Test
    @DisplayName("끝난 작업은 여러 개여도 되고, 그 뒤에 새 작업을 시작할 수 있다")
    void finishedDoNotBlock() {
        User user = userRepository.save(MemberFixture.user());
        Withdrawal blocked = Withdrawal.start(user);
        blocked.block("[]", NOW);
        Withdrawal rolledBack = Withdrawal.start(user);
        rolledBack.rollBack("지갑 응답 없음", NOW);
        withdrawalRepository.saveAndFlush(blocked);
        withdrawalRepository.saveAndFlush(rolledBack);

        Withdrawal next = withdrawalRepository.saveAndFlush(Withdrawal.start(user));

        assertThat(next.getStatus()).isEqualTo(WithdrawalStatus.CHECKING);
    }

    @Test
    @DisplayName("같은 작업을 동시에 바꾸면 늦게 저장하는 쪽이 실패한다 (낙관적 락)")
    void optimisticLock() {
        User user = userRepository.save(MemberFixture.user());
        Long id = withdrawalRepository.saveAndFlush(Withdrawal.start(user)).getId();
        Withdrawal stale = withdrawalRepository.findById(id).orElseThrow();

        transactionTemplate.executeWithoutResult(status ->
                withdrawalRepository.findById(id).orElseThrow().rollBack("배치가 먼저 정리", NOW));
        stale.needRecovery("요청이 늦게 저장");

        assertThatThrownBy(() -> withdrawalRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(withdrawalRepository.findById(id).orElseThrow().getStatus()).isEqualTo(WithdrawalStatus.ROLLED_BACK);
    }
}
