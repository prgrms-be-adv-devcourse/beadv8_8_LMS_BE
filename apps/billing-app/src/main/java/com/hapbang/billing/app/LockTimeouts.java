package com.hapbang.billing.app;

import java.sql.SQLException;
import java.util.function.Supplier;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;

// Facade 진입점에서 락 대기 초과(PostgreSQL 55P03, lock_timeout)를 HP_LOCK_TIMEOUT(503)으로 바꾼다 (#273, docs/billing/CONTEXT.md 1.4).
// 공통 GlobalExceptionHandler는 6개 앱이 같은 사본이고 Billing 오류 코드를 모르므로 여기서 바꾼다.
// 트랜잭션은 UseCase가 열고 닫으므로, 이 변환은 롤백이 끝난 뒤의 예외만 바꾼다. 다른 예외는 그대로 다시 던진다.
final class LockTimeouts {

    static final String LOCK_NOT_AVAILABLE = "55P03";

    private LockTimeouts() {
    }

    static <T> T translate(Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            if (isLockTimeout(exception)) {
                throw new BillingException(BillingErrorCode.HP_LOCK_TIMEOUT, exception);
            }
            throw exception;
        }
    }

    // 원인 예외 중 SQLState가 55P03(lock_not_available)인 SQLException이 있는지 확인한다.
    // Spring·Hibernate가 감싼 예외 종류(PessimisticLockingFailureException 등)에 기대지 않는다.
    static boolean isLockTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && LOCK_NOT_AVAILABLE.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
