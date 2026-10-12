package com.hapbang.billing.out;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class BillingSchemaTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void Flyway로_12개_테이블을_생성하고_Hibernate_validate로_로딩한다() {
        assertThat(entityManager.createNativeQuery("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'billing' AND table_type = 'BASE TABLE'
                """, String.class).getResultList())
                .containsExactlyInAnyOrder(
                        "wallet", "point_ledger", "point_hold", "point_hold_detail",
                        "point_charge", "point_charge_usage", "point_refund_request", "point_refund",
                        "settlement", "exchange", "exchange_account", "exchange_payout");
        assertThat(entityManager.getEntityManagerFactory().getMetamodel().getEntities()).hasSize(12);
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
        assertThat(foreignKeys).hasSize(18);
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
}
