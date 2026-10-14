package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.billing.app.LedgerEntry;
import com.hapbang.billing.app.LedgerOperation;
import com.hapbang.billing.app.LedgerRecordUseCase;
import com.hapbang.billing.domain.BalanceBucket;
import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.LedgerTransactionType;
import com.hapbang.billing.domain.PointType;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.billing.domain.BillingReferenceType;
import com.hapbang.shared.billing.dto.HoldCommand;
import com.hapbang.shared.billing.dto.LectureSettlementCommand;
import com.hapbang.shared.billing.dto.LectureSettlementResultDto;
import com.hapbang.shared.billing.port.BillingPort;
import com.hapbang.shared.billing.port.LectureSettlementPort;
import com.hapbang.testsupport.IntegrationTest;

// 강의가 쓰는 shared LectureSettlementPort를 실제 Spring 빈으로 주입해 결과 변환·재요청·오류 전파를 실제 PostgreSQL로 검증한다 (#182).
// 등록·상태 전이 규칙 자체는 LectureSettlementIntegrationTest가 맡는다.
@IntegrationTest
class LectureSettlementPortIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T00:00:00Z");
    // 2026-10-13 15:00 KST 구매 → 2026-11-13 00:00 KST(= 2026-11-12 15:00 UTC)부터 지급
    private static final Instant PURCHASED_AT = Instant.parse("2026-10-13T06:00:00Z");
    private static final Instant PAYABLE_AT = Instant.parse("2026-11-12T15:00:00Z");
    private static final long BUYER = 1L;
    private static final long SELLER = 2L;

    @Autowired
    LectureSettlementPort lectureSettlementPort;

    @Autowired
    BillingPort billingPort;

    @Autowired
    LedgerRecordUseCase ledgerRecordUseCase;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("LectureSettlementPort는 Billing의 LectureSettlementPortAdapter 빈으로 주입된다")
    void portBean() {
        assertThat(lectureSettlementPort).isInstanceOf(LectureSettlementPortAdapter.class);
    }

    @Test
    @DisplayName("등록·취소 결과는 주문 항목·판매자·금액·지급 가능 시각·재요청 여부만 담고, 같은 요청은 재요청이다")
    void scheduleAndCancel() {
        Long buyer = wallet(BUYER);
        wallet(SELLER);
        charge(buyer, 10_000L);
        billingPort.hold(new HoldCommand(BUYER, BillingReferenceType.LECTURE_ORDER_ITEM, 5001L, 10_000L));
        LectureSettlementCommand command = new LectureSettlementCommand(BUYER, SELLER, 5001L, 10_000L, PURCHASED_AT);

        LectureSettlementResultDto scheduled = lectureSettlementPort.schedule(command);
        LectureSettlementResultDto retried = lectureSettlementPort.schedule(command);
        LectureSettlementResultDto canceled = lectureSettlementPort.cancel(5001L);
        LectureSettlementResultDto cancelRetried = lectureSettlementPort.cancel(5001L);

        assertThat(scheduled).isEqualTo(new LectureSettlementResultDto(5001L, SELLER, 10_000L, PAYABLE_AT, false));
        assertThat(retried).isEqualTo(new LectureSettlementResultDto(5001L, SELLER, 10_000L, PAYABLE_AT, true));
        assertThat(canceled).isEqualTo(new LectureSettlementResultDto(5001L, SELLER, 10_000L, PAYABLE_AT, false));
        assertThat(cancelRetried.replayed()).isTrue();
        assertThat(jdbcTemplate.queryForList("SELECT status FROM billing.settlement", String.class))
                .containsExactly("CANCELLED");
    }

    @Test
    @DisplayName("Billing 금융 오류는 바꾸지 않고 BillingException 그대로 전파한다")
    void errorsPropagate() {
        Long buyer = wallet(BUYER);
        charge(buyer, 10_000L);
        billingPort.hold(new HoldCommand(BUYER, BillingReferenceType.LECTURE_ORDER_ITEM, 5001L, 10_000L));

        // 판매자 지갑 없음
        assertThatThrownBy(() -> lectureSettlementPort.schedule(
                new LectureSettlementCommand(BUYER, SELLER, 5001L, 10_000L, PURCHASED_AT)))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.WALLET_NOT_FOUND));
        // 지급 건 없음
        assertThatThrownBy(() -> lectureSettlementPort.cancel(5001L))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(BillingErrorCode.SETTLEMENT_NOT_FOUND));
    }

    private Long wallet(Long userId) {
        return walletRepository.save(Wallet.create(userId)).getId();
    }

    private void charge(Long walletId, long amountHp) {
        Long chargeId = jdbcTemplate.queryForObject("""
                INSERT INTO billing.point_charge (wallet_id, amount_hp, payment_amount_krw, remaining_principal_hp,
                    pg_provider, pg_order_id, status, requested_at, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TOSS_PAYMENTS', ?, 'COMPLETED', ?, ?, now(), now())
                RETURNING id""", Long.class,
                walletId, amountHp, amountHp, amountHp, "order-" + walletId, Timestamp.from(T1), Timestamp.from(T1));
        ledgerRecordUseCase.record(new LedgerOperation("CHARGE:" + chargeId, LedgerTransactionType.CHARGE,
                BillingReferenceType.POINT_CHARGE, chargeId, null, T1,
                List.of(LedgerEntry.of(walletId, PointType.CHARGE, BalanceBucket.AVAILABLE, amountHp))));
    }
}
