package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.testsupport.IntegrationTest;

// 실제 PostgreSQL이 보낸 오류로 지갑 중복 판별(SQLState 23505 + 제약 이름 uk_wallet_user_id)을 검증한다.
@IntegrationTest
class WalletDuplicateViolationTest {

    private static final String INSERT_WALLET =
            "INSERT INTO billing.wallet (user_id, created_at, updated_at) VALUES (?, now(), now())";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("JPA로 같은 회원의 지갑을 다시 저장하면 지갑 중복으로 판별한다")
    void jpaDuplicateUserId() {
        walletRepository.save(Wallet.create(1L));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> walletRepository.save(Wallet.create(1L))))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(WalletCreateUseCase.isDuplicateWallet(exception)).isTrue());
    }

    @Test
    @DisplayName("회원 ID UNIQUE 위반은 지갑 중복으로 판별한다")
    void duplicateUserId() {
        jdbcTemplate.update(INSERT_WALLET, 1L);

        assertDuplicateWallet(() -> jdbcTemplate.update(INSERT_WALLET, 1L), true);
    }

    @Test
    @DisplayName("기본키 UNIQUE 위반은 지갑 중복으로 판별하지 않는다")
    void duplicatePrimaryKey() {
        Long walletId = walletRepository.save(Wallet.create(1L)).getId();

        assertDuplicateWallet(() -> jdbcTemplate.update(
                "INSERT INTO billing.wallet (id, user_id, created_at, updated_at) VALUES (?, ?, now(), now())",
                walletId, 2L), false);
    }

    @Test
    @DisplayName("CHECK 제약 위반은 지갑 중복으로 판별하지 않는다")
    void checkViolation() {
        assertDuplicateWallet(() -> jdbcTemplate.update(
                "INSERT INTO billing.wallet (user_id, charge_balance_hp, created_at, updated_at) VALUES (?, -1, now(), now())",
                1L), false);
    }

    @Test
    @DisplayName("오류 메시지가 한국어여도 서버가 보낸 제약 이름 필드로 지갑 중복을 판별한다")
    void localizedMessage() {
        // 서버 언어가 한국어일 때처럼 메시지에 'constraint "..."' 문구가 없는 UNIQUE 위반을 서버에서 발생시킨다.
        String raise = """
                DO $$
                BEGIN
                    RAISE EXCEPTION '중복된 키 값이 "%" 고유 제약 조건을 위반함', '{constraint}'
                        USING ERRCODE = 'unique_violation', CONSTRAINT = '{constraint}';
                END
                $$""";

        assertThatThrownBy(() -> jdbcTemplate.execute(raise.replace("{constraint}", "uk_wallet_user_id")))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, exception -> {
                    assertThat(exception.getMostSpecificCause().getMessage()).doesNotContain("constraint \"");
                    assertThat(WalletCreateUseCase.isDuplicateWallet(exception)).isTrue();
                });
        assertDuplicateWallet(() -> jdbcTemplate.execute(raise.replace("{constraint}", "pk_wallet")), false);
    }

    // SQL 실행이 무결성 오류로 실패하고 그 오류의 지갑 중복 판별 결과가 기대값과 같은지 확인한다.
    private void assertDuplicateWallet(Runnable statement, boolean expected) {
        assertThatThrownBy(statement::run)
                .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                        exception -> assertThat(WalletCreateUseCase.isDuplicateWallet(exception)).isEqualTo(expected));
    }
}
