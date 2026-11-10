package com.hapbang.billing.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;

import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.billing.domain.BudgetRelease;
import com.hapbang.billing.domain.BudgetReservation;
import com.hapbang.billing.domain.PromotionFunding;
import com.hapbang.billing.domain.PromotionFundingSourceType;
import com.hapbang.billing.domain.PromotionWithdrawal;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

// 프로모션 지갑·원장 칸과 충전·회수·예산 예약·해제 기록의 불변식을 DB 제약(CHECK·UNIQUE·FK)이 앱을 거치지 않은 SQL에서도 막는지 검증한다.
@RepositoryTest
class PromotionConstraintsTest {

    @Autowired
    private EntityManager entityManager;

    private Wallet member;
    private Long promotionWalletId;
    private Long platformWalletId;
    private PromotionFunding funding;
    private PromotionWithdrawal withdrawal;
    private BudgetReservation reservation;
    private BudgetRelease release;

    @BeforeEach
    void persistRows() {
        member = persist(Wallet.create(100L));
        promotionWalletId = walletIdOf("PROMOTION");
        platformWalletId = walletIdOf("PLATFORM");
        funding = persist(PromotionFunding.of("PF-1", promotionWalletId, 1_000L, PromotionFundingSourceType.ADMIN_GRANT,
                "결재-1", "메모", 9L));
        withdrawal = persist(PromotionWithdrawal.of("PW-1", promotionWalletId, 100L, "결재-2", null, 9L));
        reservation = persist(BudgetReservation.reserve("coupon-42-reserve-v1", promotionWalletId, 42L, 1, 600L));
        entityManager.flush();
        release = persist(BudgetRelease.of("coupon-42-release-v1", reservation.getId(), 100L, "UNISSUED:10", "발급 종료"));
        entityManager.flush();
    }

    @Test
    void 프로모션_지갑은_Flyway가_만든_한_행뿐이고_더_만들_수_없다() {
        assertThat(entityManager.createNativeQuery(
                "SELECT count(*) FROM billing.wallet WHERE wallet_type = 'PROMOTION' AND user_id IS NULL")
                .getSingleResult()).isEqualTo(1L);
        reject("""
                INSERT INTO billing.wallet (wallet_type, user_id, created_at, updated_at)
                VALUES ('PROMOTION', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "23505", "uk_wallet_promotion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"promotion_balance_hp", "reserved_promotion_hp", "held_promotion_hp"})
    void 프로모션_HP_칸은_음수가_될_수_없다(String column) {
        reject("UPDATE billing.wallet SET " + column + " = -1 WHERE id = ?", "23514", "ck_wallet_", promotionWalletId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"promotion_balance_hp = 1", "reserved_promotion_hp = 1", "held_promotion_hp = 1"})
    void 회원_지갑과_플랫폼_지갑에는_프로모션_HP가_없다(String assignment) {
        reject("UPDATE billing.wallet SET " + assignment + " WHERE id = ?", "23514", "ck_wallet_owner", member.getId());
        reject("UPDATE billing.wallet SET " + assignment + " WHERE id = ?", "23514", "ck_wallet_owner",
                platformWalletId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"charge_balance_hp = 1", "revenue_balance_hp = 1", "held_charge_hp = 1",
            "held_revenue_hp = 1", "fee_balance_hp = 1", "user_id = 999"})
    void 프로모션_지갑은_회원이_없고_프로모션_HP만_있다(String assignment) {
        reject("UPDATE billing.wallet SET " + assignment + " WHERE id = ?", "23514", "ck_wallet_owner",
                promotionWalletId);
    }

    @Test
    void 프로모션_지갑에는_프로모션_HP_칸만_채울_수_있다() {
        entityManager.createNativeQuery("""
                UPDATE billing.wallet SET promotion_balance_hp = 1, reserved_promotion_hp = 2, held_promotion_hp = 3
                WHERE id = ?1""").setParameter(1, promotionWalletId).executeUpdate();
        assertThat((Object[]) entityManager.createNativeQuery("""
                SELECT promotion_balance_hp, reserved_promotion_hp, held_promotion_hp FROM billing.wallet WHERE id = ?1""")
                .setParameter(1, promotionWalletId).getSingleResult()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void 예약_칸은_프로모션_HP만_있고_프로모션_원장_유형은_정해진_칸만_바꾼다() {
        String insert = """
                INSERT INTO billing.point_ledger
                    (wallet_id, point_type, balance_bucket, amount_hp, transaction_type,
                     reference_type, reference_id, operation_key, idempotency_key, occurred_at, created_at)
                VALUES (?, ?, ?, 100, ?, ?, 1, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
        reject(insert, "23514", "ck_point_ledger_reserved_bucket", member.getId(), "CHARGE", "RESERVED", "HOLD",
                "LECTURE_ORDER_ITEM", "T1", "T1:1");
        reject(insert, "23514", "ck_point_ledger_promotion_transaction", promotionWalletId, "PROMOTION", "RESERVED",
                "PROMOTION_FUND", "PROMOTION_FUNDING", "T2", "T2:1");
        reject(insert, "23514", "ck_point_ledger_promotion_transaction", promotionWalletId, "PROMOTION", "HELD",
                "BUDGET_RESERVE", "COUPON_BUDGET", "T3", "T3:1");
        reject(insert, "23514", "ck_point_ledger_promotion_transaction", member.getId(), "CHARGE", "AVAILABLE",
                "PROMOTION_WITHDRAW", "PROMOTION_WITHDRAWAL", "T4", "T4:1");
        reject(insert, "23514", "ck_point_ledger_balance_bucket", member.getId(), "CHARGE", "UNDEFINED",
                "HOLD", "LECTURE_ORDER_ITEM", "T5", "T5:1");
    }

    @Test
    void 쿠폰당_예약은_하나이고_예약_작업_ID도_한_번만_쓴다() {
        BudgetReservation other = persist(BudgetReservation.reserve("coupon-43-reserve-v1", promotionWalletId, 43L, 1,
                10L));
        entityManager.flush();

        reject("UPDATE billing.budget_reservation SET coupon_id = 42 WHERE id = ?", "23505",
                "uk_budget_reservation_coupon_id", other.getId());
        reject("UPDATE billing.budget_reservation SET work_id = 'coupon-42-reserve-v1' WHERE id = ?", "23505",
                "uk_budget_reservation_work_id", other.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "amount_hp = 0, remaining_hp = 0",
            "remaining_hp = -1, released_hp = 601",
            "remaining_hp = 600, held_hp = -1, released_hp = 1",
            "remaining_hp = 601, paid_hp = -1",
            "remaining_hp = 601, released_hp = -1",
            "remaining_hp = 500",
            "remaining_hp = 400, held_hp = 100, paid_hp = 100, released_hp = 100",
            "remaining_hp = 9223372036854775807, released_hp = 1",
            "policy_version = 0"})
    void 예약_총액은_남은_예산_보류_지급_해제의_합이고_각_칸은_음수가_아니다(String assignment) {
        reject("UPDATE billing.budget_reservation SET " + assignment + " WHERE id = ?", "23514",
                "ck_budget_reservation_", reservation.getId());
    }

    @Test
    void 예약_총액을_칸끼리_옮기는_변경은_허용한다() {
        entityManager.createNativeQuery("""
                UPDATE billing.budget_reservation SET remaining_hp = 300, held_hp = 100, paid_hp = 100, released_hp = 100
                WHERE id = ?1""").setParameter(1, reservation.getId()).executeUpdate();
        assertThat(entityManager.createNativeQuery("SELECT remaining_hp FROM billing.budget_reservation WHERE id = ?1")
                .setParameter(1, reservation.getId()).getSingleResult()).isEqualTo(300L);
    }

    @Test
    void 충전_기록은_작업_ID당_하나이고_금액은_양수이며_출처와_근거가_있다() {
        PromotionFunding other = persist(PromotionFunding.of("PF-2", promotionWalletId, 1L,
                PromotionFundingSourceType.ADMIN_GRANT, "결재-3", null, 9L));
        entityManager.flush();

        reject("UPDATE billing.promotion_funding SET funding_work_id = 'PF-1' WHERE id = ?", "23505",
                "uk_promotion_funding_funding_work_id", other.getId());
        reject("UPDATE billing.promotion_funding SET amount_hp = 0 WHERE id = ?", "23514", "ck_promotion_funding_1",
                funding.getId());
        reject("UPDATE billing.promotion_funding SET source_type = 'UNDEFINED' WHERE id = ?", "23514",
                "ck_promotion_funding_source_type", funding.getId());
        reject("UPDATE billing.promotion_funding SET source_ref = NULL WHERE id = ?", "23502", "source_ref",
                funding.getId());
        reject("UPDATE billing.promotion_funding SET funded_by = NULL WHERE id = ?", "23502", "funded_by",
                funding.getId());
        reject("UPDATE billing.promotion_funding SET wallet_id = -1 WHERE id = ?", "23503",
                "fk_promotion_funding_wallet_id", funding.getId());
    }

    @Test
    void 회수_기록은_작업_ID당_하나이고_금액은_양수이며_근거가_있다() {
        PromotionWithdrawal other = persist(PromotionWithdrawal.of("PW-2", promotionWalletId, 1L, "결재-4", null, 9L));
        entityManager.flush();

        reject("UPDATE billing.promotion_withdrawal SET withdrawal_work_id = 'PW-1' WHERE id = ?", "23505",
                "uk_promotion_withdrawal_withdrawal_work_id", other.getId());
        reject("UPDATE billing.promotion_withdrawal SET amount_hp = 0 WHERE id = ?", "23514",
                "ck_promotion_withdrawal_1", withdrawal.getId());
        reject("UPDATE billing.promotion_withdrawal SET reference_no = NULL WHERE id = ?", "23502", "reference_no",
                withdrawal.getId());
    }

    @Test
    void 해제_기록은_작업_ID당_하나이고_금액은_양수이며_있는_예약을_가리킨다() {
        BudgetRelease other = persist(BudgetRelease.of("coupon-42-release-v2", reservation.getId(), 1L, "ADMIN", null));
        entityManager.flush();

        reject("UPDATE billing.budget_release SET work_id = 'coupon-42-release-v1' WHERE id = ?", "23505",
                "uk_budget_release_work_id", other.getId());
        reject("UPDATE billing.budget_release SET amount_hp = 0 WHERE id = ?", "23514", "ck_budget_release_1",
                release.getId());
        reject("UPDATE billing.budget_release SET budget_reservation_id = -1 WHERE id = ?", "23503",
                "fk_budget_release_budget_reservation_id", release.getId());
        reject("UPDATE billing.budget_release SET target = NULL WHERE id = ?", "23502", "target", release.getId());
    }

    private Long walletIdOf(String walletType) {
        return ((Number) entityManager.createNativeQuery("SELECT id FROM billing.wallet WHERE wallet_type = ?1")
                .setParameter(1, walletType).getSingleResult()).longValue();
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    // PostgreSQL 제약 위반은 트랜잭션을 abort하므로 각 실패를 savepoint로 격리해 실제 DB 제약과 SQLSTATE를 확인한다.
    private void reject(String sql, String state, String message, Object... parameters) {
        entityManager.unwrap(Session.class).doWork(connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                assertThatThrownBy(statement::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> {
                            assertThat(((SQLException) error).getSQLState()).isEqualTo(state);
                            assertThat(error.getMessage()).contains(message);
                        });
            } finally {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            }
        });
    }
}
