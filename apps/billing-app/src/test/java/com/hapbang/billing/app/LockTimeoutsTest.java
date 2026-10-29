package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// 락 대기 초과(55P03)만 HP_LOCK_TIMEOUT으로 바꾸고 나머지 예외는 그대로 두는지 확인한다 (#273).
class LockTimeoutsTest {

    @Test
    @DisplayName("원인에 SQLState 55P03이 있으면 원인을 담은 BillingException(HP_LOCK_TIMEOUT)으로 바꾼다")
    void lockTimeout() {
        CannotAcquireLockException failure = new CannotAcquireLockException("lock",
                new RuntimeException(new SQLException("canceling statement due to lock timeout", "55P03")));

        assertThatThrownBy(() -> LockTimeouts.translate(() -> {
            throw failure;
        }))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.HP_LOCK_TIMEOUT))
                .hasCause(failure);
    }

    @Test
    @DisplayName("다른 SQLState(교착 상태 40P01, UNIQUE 위반 23505)와 업무 예외는 그대로 다시 던진다")
    void otherFailures() {
        RuntimeException deadlock = new CannotAcquireLockException("deadlock", new SQLException("deadlock", "40P01"));
        RuntimeException unique = new DataIntegrityViolationException("unique", new SQLException("dup", "23505"));
        RuntimeException business = new BillingException(BillingErrorCode.INSUFFICIENT_BALANCE);

        for (RuntimeException failure : new RuntimeException[] {deadlock, unique, business}) {
            assertThatThrownBy(() -> LockTimeouts.translate(() -> {
                throw failure;
            })).isSameAs(failure);
        }
    }

    @Test
    @DisplayName("성공하면 결과를 그대로 돌려준다")
    void success() {
        assertThat(LockTimeouts.translate(() -> "ok")).isEqualTo("ok");
    }
}
