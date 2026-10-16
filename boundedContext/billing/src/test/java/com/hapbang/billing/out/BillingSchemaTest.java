package com.hapbang.billing.out;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;

@RepositoryTest
class BillingSchemaTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void Flyway로_13개_테이블을_생성하고_Hibernate_validate로_로딩한다() {
        assertThat(entityManager.createNativeQuery("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'billing' AND table_type = 'BASE TABLE'
                """, String.class).getResultList())
                .containsExactlyInAnyOrder(
                        "wallet", "point_ledger", "point_hold", "point_hold_detail",
                        "point_charge", "point_charge_usage", "point_refund_request", "point_refund",
                        "settlement", "exchange", "exchange_account", "exchange_payout",
                        // 가상 송금 Mock 은행 기록 (#288)
                        "mock_payout_transfer");
        assertThat(entityManager.getEntityManagerFactory().getMetamodel().getEntities()).hasSize(13);
        assertThat(entityManager.getEntityManagerFactory().getProperties().get("hibernate.hbm2ddl.auto"))
                .isEqualTo("validate");
    }

    @Test
    void 모든_시각은_TIMESTAMPTZ이고_금액은_BIGINT다() {
        List<?> invalidTimes = entityManager.createNativeQuery("""
                SELECT table_name, column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'billing' AND column_name LIKE '%\\_at' ESCAPE '\\'
                  AND data_type <> 'timestamp with time zone'
                """).getResultList();
        assertThat(invalidTimes).isEmpty();

        List<?> invalidAmounts = entityManager.createNativeQuery("""
                SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'billing'
                  AND (column_name LIKE '%\\_hp' ESCAPE '\\' OR column_name LIKE '%\\_krw' ESCAPE '\\')
                  AND (data_type <> 'bigint' OR is_nullable <> 'NO')
                """).getResultList();
        assertThat(invalidAmounts).isEmpty();
        Object[] rate = (Object[]) entityManager.createNativeQuery("""
                SELECT numeric_precision, numeric_scale FROM information_schema.columns
                WHERE table_schema = 'billing' AND table_name = 'exchange' AND column_name = 'fee_rate'
                """).getSingleResult();
        assertThat(((Number) rate[0]).intValue()).isEqualTo(5);
        assertThat(((Number) rate[1]).intValue()).isEqualTo(4);
    }

    @Test
    void 모든_FK는_Billing_내부를_참조하며_삭제를_전파하지_않는다() {
        List<?> foreignKeys = entityManager.createNativeQuery("""
                SELECT target_ns.nspname, constraint_row.confdeltype::text
                FROM pg_constraint constraint_row
                JOIN pg_class source_table ON source_table.oid = constraint_row.conrelid
                JOIN pg_namespace source_ns ON source_ns.oid = source_table.relnamespace
                JOIN pg_class target_table ON target_table.oid = constraint_row.confrelid
                JOIN pg_namespace target_ns ON target_ns.oid = target_table.relnamespace
                WHERE constraint_row.contype = 'f' AND source_ns.nspname = 'billing'
                """).getResultList();
        // 18개 + 보류 상세의 지정 충전 건(target_charge_id → point_charge, #241)
        assertThat(foreignKeys).hasSize(19);
        for (Object row : foreignKeys) {
            assertThat((Object[]) row).containsExactly("billing", "a"); // NO ACTION
        }
    }

    @Test
    void 작업키_인덱스는_중복을_허용하고_원장항목_멱등키는_필수다() {
        assertThat(entityManager.createNativeQuery("""
                SELECT index_row.indisunique
                FROM pg_index index_row
                JOIN pg_class table_row ON table_row.oid = index_row.indrelid
                JOIN pg_namespace ns ON ns.oid = table_row.relnamespace
                WHERE ns.nspname = 'billing' AND table_row.relname = 'point_ledger'
                  AND pg_get_indexdef(index_row.indexrelid) LIKE '%(operation_key)'
                """, Boolean.class).getResultList()).containsExactly(false);
        assertThat(entityManager.createNativeQuery("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'billing' AND column_name = 'idempotency_key'
                """, String.class).getResultList()).hasSize(7).containsOnly("NO");
    }

    @Test
    void 삭제필드와_다른_도메인_테이블을_추가하지_않는다() {
        assertThat(entityManager.createNativeQuery("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'billing' AND column_name IN ('deleted_at', 'deleted')
                """).getResultList()).isEmpty();
        assertThat(entityManager.createNativeQuery("""
                SELECT table_schema FROM information_schema.tables
                WHERE table_type = 'BASE TABLE'
                  AND table_schema NOT IN ('billing', 'public', 'pg_catalog', 'information_schema')
                """).getResultList()).isEmpty();
    }

    // #116에서 JPA 연관관계를 ID 필드로 바꾼 13개: 엔티티의 @Column 이름·NULL 허용이 Flyway 컬럼과 같고, 그 컬럼의 DB FK가 남아 있다.
    @ParameterizedTest
    @CsvSource({
            "PointLedger, walletId, point_ledger, wallet_id, false",
            "PointLedger, originalLedgerId, point_ledger, original_ledger_id, true",
            "PointHold, walletId, point_hold, wallet_id, false",
            "PointCharge, walletId, point_charge, wallet_id, false",
            "PointChargeUsage, chargeId, point_charge_usage, charge_id, false",
            "PointRefundRequest, walletId, point_refund_request, wallet_id, false",
            "PointRefundRequest, pointHoldId, point_refund_request, point_hold_id, true",
            "PointRefund, walletId, point_refund, wallet_id, false",
            "PointRefund, chargeId, point_refund, charge_id, false",
            "Settlement, receiverWalletId, settlement, receiver_wallet_id, false",
            "Settlement, pointHoldId, settlement, point_hold_id, false",
            "Exchange, walletId, exchange, wallet_id, false",
            "Exchange, pointHoldId, exchange, point_hold_id, true",
            // #241: 보류 상세가 처리할 충전 건(다른 Aggregate라 ID 참조)
            "PointHoldDetail, targetChargeId, point_hold_detail, target_charge_id, true"})
    void ID로_바꾼_참조_필드는_기존_컬럼과_NULL_제약과_FK를_그대로_쓴다(String entity, String field, String table,
            String column, boolean nullable) throws Exception {
        Field idField = Class.forName("com.hapbang.billing.domain." + entity).getDeclaredField(field);
        Column mapping = idField.getAnnotation(Column.class);
        assertThat(idField.getType()).isEqualTo(Long.class);
        assertThat(mapping.name()).isEqualTo(column);
        assertThat(mapping.nullable()).isEqualTo(nullable);

        assertThat(entityManager.createNativeQuery("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'billing' AND table_name = :table AND column_name = :column
                """, String.class).setParameter("table", table).setParameter("column", column).getSingleResult())
                .isEqualTo(nullable ? "YES" : "NO");
        assertThat(entityManager.createNativeQuery("""
                SELECT count(*) FROM pg_constraint constraint_row
                JOIN pg_class source_table ON source_table.oid = constraint_row.conrelid
                JOIN pg_namespace ns ON ns.oid = source_table.relnamespace
                JOIN pg_attribute attribute_row ON attribute_row.attrelid = constraint_row.conrelid
                    AND attribute_row.attnum = ANY (constraint_row.conkey)
                WHERE constraint_row.contype = 'f' AND ns.nspname = 'billing'
                  AND source_table.relname = :table AND attribute_row.attname = :column
                """, Long.class).setParameter("table", table).setParameter("column", column).getSingleResult())
                .isEqualTo(1L);
    }
}
