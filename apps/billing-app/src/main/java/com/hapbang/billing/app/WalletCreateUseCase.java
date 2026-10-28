package com.hapbang.billing.app;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;

// 회원 지갑을 회원당 하나만 생성한다. 가입 이벤트와 지갑 조회가 같은 경로를 써서 같은 결과를 만든다.
@Service
public class WalletCreateUseCase {

    static final String WALLET_USER_ID_UNIQUE = "uk_wallet_user_id";
    static final String UNIQUE_VIOLATION = "23505";

    private final WalletRepository walletRepository;
    private final TransactionTemplate newTransaction;

    // 지갑 저장소와 생성 전용 REQUIRES_NEW 트랜잭션 템플릿을 준비한다.
    public WalletCreateUseCase(WalletRepository walletRepository, PlatformTransactionManager transactionManager) {
        this.walletRepository = walletRepository;
        // INSERT 실패로 오류 상태가 된 PostgreSQL 트랜잭션에서 재조회하지 않도록 생성만 별도 트랜잭션에서 실행한다.
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 회원의 지갑을 반환하고, 없으면 네 가지 잔액이 0인 지갑을 만든다.
    public Wallet getOrCreate(Long userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> create(userId));
    }

    // 별도 트랜잭션에서 지갑을 저장하고, uk_wallet_user_id 충돌이면 롤백 후 먼저 커밋된 지갑을 조회해 반환한다.
    private Wallet create(Long userId) {
        try {
            return newTransaction.execute(status -> walletRepository.save(Wallet.create(userId)));
        } catch (DataIntegrityViolationException exception) {
            // 회원 ID UNIQUE 충돌이 아닌 DB 오류는 중복 요청으로 처리하지 않고 그대로 던진다.
            if (!isDuplicateWallet(exception)) {
                throw exception;
            }
            return walletRepository.findByUserId(userId)
                    .orElseThrow(() -> exception);
        }
    }

    // 예외 원인의 PostgreSQL 오류가 UNIQUE 위반(23505)이고 서버가 보낸 제약 이름이 uk_wallet_user_id인지 확인한다.
    static boolean isDuplicateWallet(DataIntegrityViolationException exception) {
        // 오류 메시지 문자열은 서버 언어(lc_messages)에 따라 달라지므로 구조화된 제약 이름 필드를 쓴다.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgresException) {
                ServerErrorMessage serverError = postgresException.getServerErrorMessage();
                return serverError != null
                        && UNIQUE_VIOLATION.equals(serverError.getSQLState())
                        && WALLET_USER_ID_UNIQUE.equals(serverError.getConstraint());
            }
        }
        return false;
    }
}
