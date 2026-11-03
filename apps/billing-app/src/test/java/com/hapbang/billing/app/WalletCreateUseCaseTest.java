package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.domain.WalletWithdrawal;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.billing.out.WalletUserLock;
import com.hapbang.billing.out.WalletWithdrawalRepository;
import com.hapbang.shared.billing.domain.WithdrawalCloseOutcome;
import com.hapbang.shared.billing.domain.WithdrawalReopenOutcome;

// 지갑 생성 UseCase의 트랜잭션 순서와 중복 판별을 DB 없이 검증한다.
class WalletCreateUseCaseTest {

    WalletRepository walletRepository = mock(WalletRepository.class);
    WalletWithdrawalRepository walletWithdrawalRepository = mock(WalletWithdrawalRepository.class);
    WalletUserLock walletUserLock = mock(WalletUserLock.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    WalletCreateUseCase useCase;

    // 목 트랜잭션 매니저를 연결한 UseCase를 만든다.
    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        useCase = new WalletCreateUseCase(walletRepository, walletWithdrawalRepository, walletUserLock,
                transactionManager);
    }

    @Test
    @DisplayName("지갑이 있으면 새로 만들지 않고 기존 지갑을 반환한다")
    void existingWallet() {
        Wallet wallet = Wallet.create(1L);
        given(walletRepository.findByUserId(1L)).willReturn(Optional.of(wallet));

        assertThat(useCase.getOrCreate(1L)).isSameAs(wallet);
        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("회원 락을 기다리는 동안 다른 요청이 지갑을 만들었으면 새로 만들지 않고 그 지갑을 반환한다 (#623)")
    void createdWhileWaitingForLock() {
        Wallet existing = Wallet.create(1L);
        given(walletRepository.findByUserId(1L)).willReturn(Optional.empty(), Optional.of(existing));

        assertThat(useCase.getOrCreate(1L)).isSameAs(existing);
        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("회원의 마지막 닫기가 다시 열리지 않았으면 그 탈퇴 작업으로 닫힌 지갑을 만든다 (#623)")
    void createClosedWallet() {
        Instant closedAt = Instant.parse("2026-11-01T00:00:00Z");
        given(walletRepository.findByUserId(1L)).willReturn(Optional.empty());
        given(walletWithdrawalRepository.findFirstByUserIdAndCloseOutcomeOrderByClosedAtDescWithdrawalIdDesc(1L,
                WithdrawalCloseOutcome.CLOSED)).willReturn(Optional.of(WalletWithdrawal.closed(77L, 1L, closedAt)));
        given(walletRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Wallet wallet = useCase.getOrCreate(1L);

        assertThat(wallet.isClosedBy(77L)).isTrue();
        assertThat(wallet.getClosedAt()).isEqualTo(closedAt);
    }

    @Test
    @DisplayName("회원의 마지막 닫기가 다시 열렸으면 열린 지갑을 만든다 (#623)")
    void createOpenWalletAfterReopen() {
        WalletWithdrawal reopened = WalletWithdrawal.closed(77L, 1L, Instant.parse("2026-11-01T00:00:00Z"));
        reopened.recordReopen(WithdrawalReopenOutcome.REOPENED, Instant.parse("2026-11-02T00:00:00Z"));
        given(walletRepository.findByUserId(1L)).willReturn(Optional.empty());
        given(walletWithdrawalRepository.findFirstByUserIdAndCloseOutcomeOrderByClosedAtDescWithdrawalIdDesc(1L,
                WithdrawalCloseOutcome.CLOSED)).willReturn(Optional.of(reopened));
        ArgumentCaptor<Wallet> saved = ArgumentCaptor.forClass(Wallet.class);
        given(walletRepository.save(saved.capture())).willAnswer(invocation -> invocation.getArgument(0));

        useCase.getOrCreate(1L);

        assertThat(saved.getValue().isClosed()).isFalse();
    }

    @Test
    @DisplayName("회원 ID UNIQUE 제약 위반이면 먼저 만들어진 지갑을 다시 조회해 반환한다")
    void duplicateWallet() {
        Wallet existing = Wallet.create(1L);
        given(walletRepository.findByUserId(1L)).willReturn(Optional.empty(), Optional.empty(),
                Optional.of(existing));
        given(walletRepository.save(any())).willThrow(violation("uk_wallet_user_id"));

        assertThat(useCase.getOrCreate(1L)).isSameAs(existing);

        // 생성 트랜잭션을 롤백으로 끝낸 뒤에 기존 지갑을 다시 조회한다. 실패한 트랜잭션은 커밋하지 않는다.
        InOrder order = inOrder(walletRepository, transactionManager, walletUserLock);
        order.verify(walletRepository).findByUserId(1L);
        order.verify(transactionManager).getTransaction(any());
        order.verify(walletUserLock).lock(1L);
        order.verify(walletRepository).findByUserId(1L);
        order.verify(walletRepository).save(any());
        order.verify(transactionManager).rollback(any());
        order.verify(walletRepository).findByUserId(1L);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("회원 ID UNIQUE 제약이 아닌 DB 오류는 중복 요청으로 처리하지 않고 그대로 던진다")
    void otherIntegrityViolation() {
        DataIntegrityViolationException exception = violation("ck_wallet_1");
        given(walletRepository.findByUserId(1L)).willReturn(Optional.empty());
        given(walletRepository.save(any())).willThrow(exception);

        assertThatThrownBy(() -> useCase.getOrCreate(1L)).isSameAs(exception);
        verify(transactionManager).rollback(any());
        verify(walletRepository, times(2)).findByUserId(1L);
    }

    @Test
    @DisplayName("기본키 등 다른 UNIQUE 제약 위반도 지갑 중복으로 처리하지 않는다")
    void otherUniqueViolation() {
        DataIntegrityViolationException exception = violation("pk_wallet");
        given(walletRepository.findByUserId(1L)).willReturn(Optional.empty());
        given(walletRepository.save(any())).willThrow(exception);

        assertThatThrownBy(() -> useCase.getOrCreate(1L)).isSameAs(exception);
        verify(walletRepository, times(2)).findByUserId(1L);
    }

    @Test
    @DisplayName("UNIQUE 충돌 뒤 다시 조회해도 지갑이 없으면 원래 예외를 던진다")
    void duplicateWallet_notVisible() {
        DataIntegrityViolationException exception = violation("uk_wallet_user_id");
        given(walletRepository.findByUserId(1L)).willReturn(Optional.empty());
        given(walletRepository.save(any())).willThrow(exception);

        assertThatThrownBy(() -> useCase.getOrCreate(1L)).isSameAs(exception);
    }

    @Test
    @DisplayName("제약 이름을 알 수 없는 무결성 오류도 그대로 던진다")
    void unknownIntegrityViolation() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException("unknown");
        given(walletRepository.findByUserId(1L)).willReturn(Optional.empty());
        given(walletRepository.save(any())).willThrow(exception);

        assertThatThrownBy(() -> useCase.getOrCreate(1L)).isSameAs(exception);
    }

    @Test
    @DisplayName("연결 오류 등 무결성 위반이 아닌 DB 오류는 잡지 않고 재조회 없이 그대로 전파한다")
    void connectionFailure() {
        DataAccessResourceFailureException exception = new DataAccessResourceFailureException("connection lost");
        given(walletRepository.findByUserId(1L)).willReturn(Optional.empty());
        given(walletRepository.save(any())).willThrow(exception);

        assertThatThrownBy(() -> useCase.getOrCreate(1L)).isSameAs(exception);
        verify(transactionManager).rollback(any());
        verify(walletRepository, times(2)).findByUserId(1L);
    }

    @Test
    @DisplayName("Hibernate가 메시지에서 뽑은 제약 이름만 있고 PostgreSQL 오류가 없으면 중복으로 보지 않는다")
    void hibernateConstraintNameOnly() {
        ConstraintViolationException hibernate = new ConstraintViolationException(
                "constraint violation", new SQLException("violation", "23505"), "uk_wallet_user_id");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("constraint violation", hibernate);

        assertThat(WalletCreateUseCase.isDuplicateWallet(exception)).isFalse();
    }

    @Test
    @DisplayName("메시지가 한국어여도 SQLState 23505와 구조화된 제약 이름으로 지갑 중복을 판별한다")
    void structuredConstraintName_localeIndependent() {
        assertThat(WalletCreateUseCase.isDuplicateWallet(violation("23505", "uk_wallet_user_id"))).isTrue();
        assertThat(WalletCreateUseCase.isDuplicateWallet(violation("23505", "pk_wallet"))).isFalse();
        assertThat(WalletCreateUseCase.isDuplicateWallet(violation("23514", "uk_wallet_user_id"))).isFalse();
        assertThat(WalletCreateUseCase.isDuplicateWallet(violation("23505", null))).isFalse();
    }

    // 지정한 제약 이름의 UNIQUE 위반(23505) 예외를 만든다.
    private DataIntegrityViolationException violation(String constraintName) {
        return violation("23505", constraintName);
    }

    // Hibernate 예외 안에 PostgreSQL 드라이버 예외가 들어 있는, 실제와 같은 원인 체인을 만든다.
    private DataIntegrityViolationException violation(String sqlState, String constraintName) {
        SQLException postgres = postgresError(sqlState, constraintName);
        ConstraintViolationException hibernate = new ConstraintViolationException(
                "could not execute statement", postgres, null);
        return new DataIntegrityViolationException("could not execute statement", hibernate);
    }

    // 한국어 메시지와 구조화된 SQLState·제약 이름을 가진 PostgreSQL 드라이버 예외를 만든다.
    private SQLException postgresError(String sqlState, String constraintName) {
        // 드라이버는 billing에서 compileOnly라 테스트 컴파일 경로에 없어 실행 경로(test-support runtimeOnly)의 클래스로 만든다.
        // 서버 오류 형식: 필드 타입 문자 + 값 + NUL (S: 심각도, C: SQLState, M: 메시지, n: 제약 이름)
        String fields = "SERROR\0C" + sqlState + "\0M중복된 키 값이 고유 제약 조건을 위반함\0"
                + (constraintName == null ? "" : "n" + constraintName + "\0");
        try {
            Class<?> serverErrorType = Class.forName("org.postgresql.util.ServerErrorMessage");
            Object serverError = serverErrorType.getConstructor(String.class).newInstance(fields);
            return (SQLException) Class.forName("org.postgresql.util.PSQLException")
                    .getConstructor(serverErrorType).newInstance(serverError);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("PostgreSQL 드라이버 예외를 만들 수 없습니다.", exception);
        }
    }
}
