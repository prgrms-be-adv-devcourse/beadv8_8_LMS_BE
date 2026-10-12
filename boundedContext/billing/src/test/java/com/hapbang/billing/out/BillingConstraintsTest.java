package com.hapbang.billing.out;

import static com.hapbang.billing.fixture.BillingFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;

import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.billing.domain.*;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class BillingConstraintsTest {

    @Autowired
    private EntityManager entityManager;

    private Wallet wallet;
    private PointCharge charge;
    private PointHold hold;
    private PointLedger ledger;
    private PointChargeUsage usage;
    private PointRefundRequest request;
    private PointRefund refund;
    private PointHoldDetail detail;
    private Settlement settlement;
    private Exchange exchange;
    private ExchangeAccount account;
    private ExchangePayout payout;

    @BeforeEach
    void persistRows() {
        wallet = persist(Wallet.create(100L));
        charge = persist(charge(wallet).build());
        hold = persist(hold(wallet).build());
        ledger = persist(ledger(wallet).build());
        usage = persist(usage(charge, hold).build());
        request = persist(refundRequest(wallet).build());
        refund = persist(refund(wallet, charge, request).build());
        detail = persist(detail(hold).build());
        settlement = persist(settlement(wallet, hold).build());
        exchange = persist(exchange(wallet).build());
        account = persist(account(exchange).build());
        payout = persist(payout(exchange).build());
        entityManager.flush();
    }

    @Test
    void 회원당_지갑은_하나다() {
        reject("""
                INSERT INTO billing.wallet (user_id, created_at, updated_at)
                VALUES (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "23505", "uk_wallet_user_id", wallet.getUserId());
    }

    @Test
    void DB에서도_지갑_잔액의_기본값은_0이다() {
        entityManager.createNativeQuery("""
                INSERT INTO billing.wallet (user_id, created_at, updated_at)
                VALUES (101, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).executeUpdate();
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT charge_balance_hp, revenue_balance_hp, held_charge_hp, held_revenue_hp
                FROM billing.wallet WHERE user_id = 101
                """).getSingleResult();
        assertThat(row).containsExactly(0L, 0L, 0L, 0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"charge_balance_hp", "revenue_balance_hp", "held_charge_hp", "held_revenue_hp"})
    void 지갑의_네_잔액은_음수가_될_수_없다(String column) {
        reject("UPDATE billing.wallet SET " + column + " = -1 WHERE id = ?",
                "23514", "ck_wallet_", wallet.getId());
    }

    @Test
    void 원장의_0_HP와_멱등키_중복과_NULL을_거부한다() {
        String insert = """
                INSERT INTO billing.point_ledger
                    (wallet_id, point_type, balance_bucket, amount_hp, transaction_type,
                     reference_type, reference_id, operation_key, idempotency_key, occurred_at, created_at)
                SELECT wallet_id, point_type, balance_bucket, ?, transaction_type,
                       reference_type, reference_id, operation_key, ?, occurred_at, created_at
                FROM billing.point_ledger WHERE id = ?
                """;
        reject(insert, "23514", "ck_point_ledger_1", 0L, "zero-line", ledger.getId());
        reject(insert, "23505", "uk_point_ledger_idempotency_key", 1L, ledger.getIdempotencyKey(), ledger.getId());
        reject(insert, "23502", "idempotency_key", 1L, null, ledger.getId());
    }

    @Test
    void 원장은_SQL로도_수정하거나_삭제할_수_없다() {
        reject("UPDATE billing.point_ledger SET amount_hp = 1 WHERE id = ?",
                "23514", "append-only", ledger.getId());
        reject("DELETE FROM billing.point_ledger WHERE id = ?",
                "23514", "append-only", ledger.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "original_charge_hp = -1", "original_revenue_hp = -1",
            "held_charge_hp = -1", "held_revenue_hp = -1",
            "held_charge_hp = 4001", "held_revenue_hp = 1001"})
    void 잔여_보류는_음수이거나_최초_보류를_초과할_수_없다(String assignment) {
        reject("UPDATE billing.point_hold SET " + assignment + " WHERE id = ?",
                "23514", "ck_point_hold_", hold.getId());
    }

    @Test
    void 같은_거래대상의_재보류는_별도_멱등키로_기록할_수_있다() {
        PointHold second = persist(hold(wallet).idempotencyKey("hold-100-again").build());
        entityManager.flush();
        assertThat(second.getId()).isNotEqualTo(hold.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "charge_amount_hp = -1", "revenue_amount_hp = -1",
            "charge_amount_hp = 0, revenue_amount_hp = 0"})
    void 보류_상세의_처리금액은_양수이고_각_HP는_음수가_아니다(String assignment) {
        reject("UPDATE billing.point_hold_detail SET " + assignment + " WHERE id = ?",
                "23514", "ck_point_hold_detail_", detail.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "amount_hp = 0", "amount_hp = 1001", "payment_amount_krw = 0",
            "remaining_principal_hp = -1", "remaining_principal_hp = 10001"})
    void 충전_금액과_가용_원금을_검증한다(String assignment) {
        reject("UPDATE billing.point_charge SET " + assignment + " WHERE id = ?",
                "23514", "ck_point_charge_", charge.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "amount_hp = 0", "remaining_held_hp = -1", "consumed_hp = -1", "released_hp = -1",
            "remaining_held_hp = 3999", "consumed_hp = 1",
            "remaining_held_hp = 9223372036854775807, consumed_hp = 1"})
    void 충전_배분금액은_비음수이고_합계가_최초금액과_같아야_한다(String assignment) {
        reject("UPDATE billing.point_charge_usage SET " + assignment + " WHERE id = ?",
                "23514", "ck_point_charge_usage_", usage.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed_amount_hp = -1", "completed_amount_hp = 2001"})
    void 환불_완료액은_요청액_범위를_벗어나지_않는다(String assignment) {
        reject("UPDATE billing.point_refund_request SET " + assignment + " WHERE id = ?",
                "23514", "ck_point_refund_request_", request.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"refund_amount_hp = 0", "refund_amount_hp = -1",
            "refund_amount_krw = 0", "refund_amount_krw = -1"})
    void 개별_환불금액은_양수다(String assignment) {
        reject("UPDATE billing.point_refund SET " + assignment + " WHERE id = ?",
                "23514", "ck_point_refund_", refund.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid_amount_hp = -1", "paid_amount_hp = 2001"})
    void 지급누계는_총지급액_범위에_있다(String assignment) {
        reject("UPDATE billing.settlement SET " + assignment + " WHERE id = ?",
                "23514", "ck_settlement_", settlement.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "requested_amount_hp = 0", "requested_amount_hp = 999", "requested_amount_hp = 1001",
            "fee_amount_hp = -1", "payout_amount_krw = -1",
            "payout_amount_krw = 899",
            "fee_amount_hp = 9223372036854775807, payout_amount_krw = 1"})
    void 환전_단위와_수수료_송금액_합계를_검증한다(String assignment) {
        reject("UPDATE billing.exchange SET " + assignment + " WHERE id = ?",
                "23514", "ck_exchange_", exchange.getId());
    }

    @Test
    void 같은_충전과_보류의_배분행은_하나다() {
        reject("""
                INSERT INTO billing.point_charge_usage
                    (charge_id, point_hold_id, amount_hp, remaining_held_hp, consumed_hp,
                     released_hp, status, created_at, updated_at)
                SELECT charge_id, point_hold_id, amount_hp, remaining_held_hp, consumed_hp,
                       released_hp, status, created_at, updated_at
                FROM billing.point_charge_usage WHERE id = ?
                """, "23505", "uk_point_charge_usage_charge_id_point_hold_id", usage.getId());
    }

    @Test
    void 지급_업무키는_멱등키를_바꿔도_중복될_수_없다() {
        reject("""
                INSERT INTO billing.settlement
                    (receiver_user_id, receiver_wallet_id, point_hold_id, source_type, source_id,
                     payout_type, gross_amount_hp, paid_amount_hp, status, idempotency_key,
                     scheduled_at, created_at, updated_at)
                SELECT receiver_user_id, receiver_wallet_id, point_hold_id, source_type, source_id,
                       payout_type, gross_amount_hp, paid_amount_hp, status, 'another-settlement-key',
                       scheduled_at, created_at, updated_at
                FROM billing.settlement WHERE id = ?
                """, "23505", "uk_settlement_point_hold_id_source_type_source_id_payout_type", settlement.getId());
    }

    @Test
    void 환전당_계좌와_송금행은_각각_최대_하나다() {
        reject("""
                INSERT INTO billing.exchange_account (exchange_id, verification_status, created_at)
                VALUES (?, 'PENDING', CURRENT_TIMESTAMP)
                """, "23505", "uk_exchange_account_exchange_id", exchange.getId());
        reject("""
                INSERT INTO billing.exchange_payout
                    (exchange_id, payout_provider, payout_request_id, amount_krw, status, requested_at, created_at)
                VALUES (?, 'MOCK', 'another-mock-request', 900, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "23505", "uk_exchange_payout_exchange_id", exchange.getId());
    }

    @Test
    void 필수_FK와_멱등키와_허용하지_않은_Enum을_검증한다() {
        reject("UPDATE billing.point_hold SET wallet_id = NULL WHERE id = ?",
                "23502", "wallet_id", hold.getId());
        reject("UPDATE billing.point_hold SET wallet_id = -1 WHERE id = ?",
                "23503", "fk_point_hold_wallet_id", hold.getId());
        reject("UPDATE billing.point_hold SET idempotency_key = NULL WHERE id = ?",
                "23502", "idempotency_key", hold.getId());
        reject("UPDATE billing.point_hold SET status = 'UNDEFINED' WHERE id = ?",
                "23514", "ck_point_hold_status", hold.getId());
    }

    @ParameterizedTest
    @CsvSource({
            "point_hold, hold-100",
            "point_hold_detail, release-101",
            "point_refund_request, refund-request-1",
            "point_refund, refund-1",
            "settlement, settlement-101",
            "exchange, exchange-1"})
    void 금융_요청의_멱등키는_중복될_수_없다(String table, String key) {
        // 원본 업무키와 다른 유효 행을 만든 뒤 멱등키만 충돌시킨다.
        BillingCreatedEntity second = switch (table) {
            case "point_hold" -> persist(hold(wallet).idempotencyKey("second").build());
            case "point_hold_detail" -> persist(detail(hold).idempotencyKey("second").build());
            case "point_refund_request" -> persist(refundRequest(wallet).idempotencyKey("second").build());
            case "point_refund" -> persist(refund(wallet, charge, request).idempotencyKey("second").build());
            case "settlement" -> persist(settlement(wallet, hold).sourceId(102L).idempotencyKey("second").build());
            case "exchange" -> persist(exchange(wallet).idempotencyKey("second").build());
            default -> throw new IllegalArgumentException(table);
        };
        entityManager.flush();
        reject("UPDATE billing." + table + " SET idempotency_key = ? WHERE id = ?",
                "23505", "uk_" + table + "_idempotency_key", key, second.getId());
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    /**
     * PostgreSQL 제약 위반은 트랜잭션을 abort하므로 각 실패를 savepoint로 격리한다.
     * JPA의 상태와 무관하게 실제 DB 제약 및 SQLSTATE를 확인한다.
     */
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

