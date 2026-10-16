package com.hapbang.billing.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.billing.domain.AccountVerificationStatus;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.ExchangePayoutStatus;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.domain.MockPayoutTransferStatus;
import com.hapbang.billing.domain.HoldDetailActionType;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PayoutProvider;
import com.hapbang.billing.domain.PayoutType;
import com.hapbang.billing.domain.PgProvider;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointChargeUsageStatus;
import com.hapbang.billing.domain.PointHoldStatus;
import com.hapbang.billing.domain.PointHoldType;
import com.hapbang.billing.domain.PointRefundRequestStatus;
import com.hapbang.billing.domain.PointRefundStatus;
import com.hapbang.billing.domain.RefundManualReviewReason;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.SettlementStatus;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.domain.SettlementSourceType;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

// Enum은 이름 그대로(EnumType.STRING) 저장되므로, 상수 이름이 Flyway CHECK 허용값과 정확히 같은지 확인한다.
// 상수 이름을 바꾸거나 값을 더하고 빼면 이 테스트가 실패한다(저장값 변경 방지).
@RepositoryTest
class BillingEnumStorageTest {

    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    @Autowired
    private EntityManager entityManager;

    @Test
    void 모든_Enum_컬럼의_CHECK_허용값은_Enum_상수_이름과_같다() {
        Map<String, Class<? extends Enum<?>>> columns = new LinkedHashMap<>();
        columns.put("ck_point_ledger_point_type", PointType.class);
        columns.put("ck_point_ledger_balance_bucket", BalanceBucket.class);
        columns.put("ck_point_ledger_transaction_type", LedgerTransactionType.class);
        columns.put("ck_point_ledger_reference_type", BillingReferenceType.class);
        columns.put("ck_point_hold_reference_type", BillingReferenceType.class);
        columns.put("ck_point_hold_status", PointHoldStatus.class);
        columns.put("ck_point_hold_type", PointHoldType.class);
        columns.put("ck_point_hold_detail_reference_type", BillingReferenceType.class);
        columns.put("ck_point_hold_detail_action_type", HoldDetailActionType.class);
        columns.put("ck_point_charge_pg_provider", PgProvider.class);
        columns.put("ck_point_charge_status", PointChargeStatus.class);
        columns.put("ck_point_charge_usage_status", PointChargeUsageStatus.class);
        columns.put("ck_point_refund_request_status", PointRefundRequestStatus.class);
        columns.put("ck_point_refund_status", PointRefundStatus.class);
        columns.put("ck_point_refund_manual_review_reason", RefundManualReviewReason.class);
        columns.put("ck_settlement_source_type", SettlementSourceType.class);
        columns.put("ck_settlement_payout_type", PayoutType.class);
        columns.put("ck_settlement_status", SettlementStatus.class);
        columns.put("ck_exchange_status", ExchangeStatus.class);
        columns.put("ck_exchange_account_verification_status", AccountVerificationStatus.class);
        columns.put("ck_exchange_payout_payout_provider", PayoutProvider.class);
        columns.put("ck_exchange_payout_status", ExchangePayoutStatus.class);
        columns.put("ck_mock_payout_transfer_status", MockPayoutTransferStatus.class);

        List<String> enumChecks = entityManager.createNativeQuery("""
                SELECT constraint_row.conname FROM pg_constraint constraint_row
                JOIN pg_namespace ns ON ns.oid = constraint_row.connamespace
                WHERE ns.nspname = 'billing' AND constraint_row.contype = 'c'
                  AND pg_get_constraintdef(constraint_row.oid) ~ '''[A-Z][A-Z_]*'''
                """, String.class).getResultList().stream()
                // 보류 종류와 HP 구성이 맞는지 보는 CHECK(#241)는 Enum 값 목록이 아니라 업무 규칙이라 뺀다
                .filter(name -> !NON_ENUM_CHECKS.contains(name))
                .toList();
        // 값이 하나뿐인 CHECK는 PostgreSQL이 IN 대신 = 로 저장하므로 대문자 문자열 상수가 있는 CHECK를 모두 찾는다.
        assertThat(enumChecks).as("Enum 컬럼 CHECK 전부를 확인한다").containsExactlyInAnyOrderElementsOf(columns.keySet());

        columns.forEach((constraint, enumType) -> assertThat(allowedValues(constraint))
                .as(constraint)
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList()));
    }

    // 대문자 문자열 상수를 쓰지만 Enum 저장값 목록이 아닌 CHECK
    // ck_point_refund_5는 운영자 확인 상태면 원인이 있어야 한다는 규칙(#260)
    // ck_exchange_5는 취소 상태와 취소 시각이 함께 있어야 한다는 규칙(#285)
    // ck_mock_payout_transfer_2는 가상 송금 상태별 거래 번호·실패 코드·완료 시각 규칙(#288)
    private static final Set<String> NON_ENUM_CHECKS = Set.of("ck_point_hold_5", "ck_point_refund_5", "ck_exchange_5",
            "ck_mock_payout_transfer_2");

    private List<String> allowedValues(String constraint) {
        String definition = (String) entityManager.createNativeQuery("""
                SELECT pg_get_constraintdef(constraint_row.oid) FROM pg_constraint constraint_row
                JOIN pg_namespace ns ON ns.oid = constraint_row.connamespace
                WHERE ns.nspname = 'billing' AND constraint_row.conname = :name
                """).setParameter("name", constraint).getSingleResult();
        Matcher matcher = QUOTED.matcher(definition);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
