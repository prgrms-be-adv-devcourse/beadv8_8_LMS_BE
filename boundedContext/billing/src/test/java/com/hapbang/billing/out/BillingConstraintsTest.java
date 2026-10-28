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
    @ValueSource(strings = {"cancel_request_count = -1", "result_check_count = -1"})
    void 개별_환불의_PG_요청_횟수와_결과_재확인_횟수는_음수가_아니다(String assignment) {
        reject("UPDATE billing.point_refund SET " + assignment + " WHERE id = ?",
                "23514", "ck_point_refund_3", refund.getId());
    }

    @Test
    void PG_취소를_요청한_건은_최초_요청_시각이_있다() {
        reject("UPDATE billing.point_refund SET cancel_request_count = 1 WHERE id = ?",
                "23514", "ck_point_refund_4", refund.getId());
    }

    @Test
    void 운영자_확인_건은_원인과_전환_시각이_있고_원인은_정해진_값이다() {
        reject("UPDATE billing.point_refund SET status = 'MANUAL_REVIEW_REQUIRED' WHERE id = ?",
                "23514", "ck_point_refund_5", refund.getId());
        reject("UPDATE billing.point_refund SET manual_review_reason = 'UNDEFINED' WHERE id = ?",
                "23514", "ck_point_refund_manual_review_reason", refund.getId());
    }

    @Test
    void 한_환불_요청은_충전_건_하나를_한_번만_취소한다() {
        PointCharge second = persist(charge(wallet).pgOrderId("order-second").build());
        PointRefund other = persist(refund(wallet, second, request).idempotencyKey("refund-2").build());
        entityManager.flush();

        reject("UPDATE billing.point_refund SET charge_id = ? WHERE id = ?",
                "23505", "uk_point_refund_refund_request_id_charge_id", charge.getId(), other.getId());
    }

    // PG 취소 멱등키는 TossPayments 상점 전체에서 한 번만 쓰여야 한다. 다른 개별 환불과 같은 키는 저장할 수 없다 (#260)
    @Test
    void 개별_환불의_PG_취소_멱등키는_중복되지_않는다() {
        PointCharge second = persist(charge(wallet).pgOrderId("order-second").build());
        PointRefund other = persist(refund(wallet, second, request).idempotencyKey("refund-2").build());
        entityManager.flush();

        reject("UPDATE billing.point_refund SET idempotency_key = ? WHERE id = ?",
                "23505", "uk_point_refund_idempotency_key", refund.getIdempotencyKey(), other.getId());
    }

    // 환불 누계는 음수가 아니고, 가용 원금 + 환불 누계가 충전 금액을 넘지 않는다(같은 원금 이중 차감 방지, #260)
    @ParameterizedTest
    @ValueSource(strings = {"refunded_amount_hp = -1", "refunded_amount_hp = amount_hp - remaining_principal_hp + 1"})
    void 충전_건_환불_누계는_가용_원금과_합쳐_충전_금액을_넘지_않는다(String assignment) {
        reject("UPDATE billing.point_charge SET " + assignment + " WHERE id = ?",
                "23514", "ck_point_charge_4", charge.getId());
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

    // 승인·거절 기록은 해당 상태와 함께만 있고, 거절 사유는 앞뒤 공백 없는 1~500자다 (#294)
    @ParameterizedTest
    @ValueSource(strings = {
            "status = 'APPROVED'",
            "approved_by = 1",
            "approved_by = 1, approved_at = now()",
            "status = 'REJECTED'",
            "rejected_by = 1, rejected_at = now(), rejection_reason = 'x'",
            "status = 'REJECTED', rejected_by = 1, rejected_at = now(), rejection_reason = ' x'",
            "status = 'REJECTED', rejected_by = 1, rejected_at = now(), rejection_reason = ''",
            "status = 'CANCELED', canceled_at = now(), approved_by = 1, approved_at = now()"})
    void 환전_승인_거절_기록은_상태와_함께_있다(String assignment) {
        reject("UPDATE billing.exchange SET " + assignment + " WHERE id = ?",
                "23514", "ck_exchange_", exchange.getId());
    }

    // 취소 시각은 CANCELED에만 있고, CANCELED에는 반드시 있다 (#285)
    @ParameterizedTest
    @ValueSource(strings = {"status = 'CANCELED'", "canceled_at = now()", "status = 'UNDEFINED'"})
    void 환전_취소_상태와_취소_시각은_함께_있다(String assignment) {
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
    void 지급_원천은_보류와_지급_유형이_달라도_한_번만_정산한다() {
        reject("""
                INSERT INTO billing.settlement
                    (receiver_user_id, receiver_wallet_id, point_hold_id, source_type, source_id,
                     payout_type, gross_amount_hp, paid_amount_hp, status, idempotency_key,
                     scheduled_at, created_at, updated_at)
                SELECT receiver_user_id, receiver_wallet_id, point_hold_id, source_type, source_id,
                       'PENALTY', gross_amount_hp, paid_amount_hp, status, 'penalty-settlement-key',
                       scheduled_at, created_at, updated_at
                FROM billing.settlement WHERE id = ?
                """, "23505", "uk_settlement_source_type_source_id", settlement.getId());
    }

    @Test
    void 환전당_계좌는_하나다() {
        reject("""
                INSERT INTO billing.exchange_account (exchange_id, verification_status, created_at)
                VALUES (?, 'PENDING', CURRENT_TIMESTAMP)
                """, "23505", "uk_exchange_account_exchange_id", exchange.getId());
    }

    // 환전 1건에 송금 시도 여러 건이지만 시도 번호는 고유하고, 확정 실패가 아닌 시도는 최대 1건이다 (#304)
    @Test
    void 환전의_송금_시도는_번호가_고유하고_실패가_아닌_시도는_하나다() {
        reject(insertPayout("another-mock-request-1", 1, "FAILED"), "23505",
                "uk_exchange_payout_exchange_id_attempt_no", exchange.getId());
        reject(insertPayout("another-mock-request-2", 2, "PROCESSING"), "23505", "uk_exchange_payout_active",
                exchange.getId());
        reject(insertPayout("another-mock-request-3", 0, "FAILED"), "23514", "ck_exchange_payout_2",
                exchange.getId());
        reject(insertPayout("mock-request-1", 2, "FAILED"), "23505", "uk_exchange_payout_payout_request_id",
                exchange.getId());
    }

    @Test
    void 확정_실패한_시도_뒤에는_새_시도를_만들_수_있다() {
        entityManager.createNativeQuery("""
                UPDATE billing.exchange_payout SET status = 'FAILED', failed_at = now() WHERE id = ?1""")
                .setParameter(1, payout.getId()).executeUpdate();
        entityManager.createNativeQuery(insertPayout("another-mock-request", 2, "PROCESSING").replace("?", "?1"))
                .setParameter(1, exchange.getId()).executeUpdate();
        assertThat(entityManager.createNativeQuery("SELECT count(*) FROM billing.exchange_payout WHERE exchange_id = ?1")
                .setParameter(1, exchange.getId()).getSingleResult()).isEqualTo(2L);
    }

    // 성공이면 거래 번호·완료 시각, 확정 실패면 실패 시각이 상태와 함께 있다 (#304)
    @ParameterizedTest
    @ValueSource(strings = {
            "status = 'COMPLETED'",
            "status = 'COMPLETED', completed_at = now()",
            "completed_at = now()",
            "status = 'FAILED'",
            "failed_at = now()",
            "status = 'UNDEFINED'"})
    void 송금_시도_결과_기록은_상태와_함께_있다(String assignment) {
        reject("UPDATE billing.exchange_payout SET " + assignment + " WHERE id = ?",
                "23514", "ck_exchange_payout_", payout.getId());
    }

    // 운영자 확인이면 그 시각이 있고, 자동 확인 횟수는 0 이상, 관리자 재확인 기록은 쌍이다 (#304)
    @ParameterizedTest
    @ValueSource(strings = {
            "status = 'MANUAL_REVIEW_REQUIRED'",
            "result_check_count = -1",
            "manual_checked_by = 1",
            "manual_checked_at = now()"})
    void 송금_시도_운영자_확인_기록은_상태와_함께_있다(String assignment) {
        reject("UPDATE billing.exchange_payout SET " + assignment + " WHERE id = ?",
                "23514", "ck_exchange_payout_", payout.getId());
    }

    // 환전 COMPLETED ⇔ 완료 시각, FAILED·FINAL_FAILED ⇔ 실패 시각, FINAL_FAILED ⇔ 종료 관리자·시각·사유(1~500자, trim) (#304)
    @ParameterizedTest
    @ValueSource(strings = {"status = 'COMPLETED'", "completed_at = now()", "status = 'FAILED'", "failed_at = now()",
            "status = 'FINAL_FAILED', failed_at = now()",
            "final_failed_by = 1, final_failed_at = now(), final_failure_reason = 'x'",
            "status = 'FINAL_FAILED', failed_at = now(), final_failed_by = 1, final_failed_at = now(), "
                    + "final_failure_reason = ' x'",
            "status = 'FINAL_FAILED', final_failed_by = 1, final_failed_at = now(), final_failure_reason = 'x'"})
    void 환전_송금_완료_실패_시각은_상태와_함께_있다(String assignment) {
        reject("UPDATE billing.exchange SET " + assignment + " WHERE id = ?",
                "23514", "ck_exchange_", exchange.getId());
    }

    private static String insertPayout(String payoutRequestId, int attemptNo, String status) {
        String failedAt = "FAILED".equals(status) ? "CURRENT_TIMESTAMP" : "NULL";
        return """
                INSERT INTO billing.exchange_payout
                    (exchange_id, attempt_no, payout_provider, payout_request_id, amount_krw, status, requested_at,
                     failed_at, last_checked_at, created_at)
                VALUES (?, %d, 'MOCK', '%s', 900, '%s', CURRENT_TIMESTAMP, %s, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(attemptNo, payoutRequestId, status, failedAt);
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
            // 한 환불 요청은 충전 건 하나를 한 번만 취소하므로(#260) 다른 환불 요청의 행을 만든다
            case "point_refund" -> persist(refund(wallet, charge,
                    persist(refundRequest(wallet).idempotencyKey("second-request").build()))
                    .idempotencyKey("second").build());
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

