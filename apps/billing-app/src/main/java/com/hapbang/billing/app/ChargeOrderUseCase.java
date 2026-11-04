package com.hapbang.billing.app;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.PointCharge;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.PointChargeRepository;
import com.hapbang.billing.out.WalletRepository;

import lombok.RequiredArgsConstructor;

// 결제 전 충전 주문을 만든다 (#224). 서버가 주문 ID를 발급하고 금액을 저장하며, 승인 전이라 HP는 바꾸지 않는다(PENDING).
// 충전 금액은 1,000 HP 이상·1,000 HP 단위(루트 AGENTS.md 5장 "금액 단위")다. 최대 금액은 정책이 없어 두지 않는다.
// 금융 거래이므로 지갑을 만들지 않고, 없으면 WALLET_NOT_FOUND다.
@Service
@RequiredArgsConstructor
public class ChargeOrderUseCase {

    static final long AMOUNT_UNIT_HP = 1_000L;
    // TossPayments 주문 ID 규칙: 6~64자, 영문·숫자·-·_ . 접두사 + UUID(하이픈 제외 32자) = 38자
    static final String ORDER_ID_PREFIX = "HPCHG-";

    private final WalletRepository walletRepository;
    private final PointChargeRepository pointChargeRepository;

    @Transactional
    public ChargeResult create(Long userId, long amount) {
        if (amount < AMOUNT_UNIT_HP || amount % AMOUNT_UNIT_HP != 0) {
            throw new BillingException(BillingErrorCode.CHARGE_AMOUNT_INVALID);
        }
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_NOT_FOUND));
        String orderId = ORDER_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // 주문 ID는 uk_point_charge_pg_order_id가 최종으로 유일함을 보장한다(UUID 충돌 시 저장 실패)
        PointCharge charge = pointChargeRepository.save(PointCharge.requested(wallet.getId(), amount, orderId, now));
        return ChargeResult.from(charge);
    }
}
