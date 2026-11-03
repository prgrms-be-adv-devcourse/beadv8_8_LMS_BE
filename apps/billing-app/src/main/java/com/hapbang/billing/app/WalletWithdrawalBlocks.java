package com.hapbang.billing.app;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hapbang.billing.domain.Exchange;
import com.hapbang.billing.domain.ExchangeStatus;
import com.hapbang.billing.domain.PointChargeStatus;
import com.hapbang.billing.domain.PointRefundRequestStatus;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.ExchangeRepository;
import com.hapbang.billing.out.PointChargeRepository;
import com.hapbang.billing.out.PointRefundRequestRepository;
import com.hapbang.shared.member.dto.WithdrawalBlockReasonDto;

import lombok.RequiredArgsConstructor;

// 지갑이 적는 회원 탈퇴 막힌 이유 (#623, docs/api/internal-withdrawals.md 2·4장). 지갑은 자기 것(잔액, 처리 중인 환불·환전·충전)만 적고,
// 출연·강의 보류(보류 HP)는 그 모듈이 적으므로 여기서 이유로 만들지 않는다. 지갑 락을 잡은 트랜잭션 안에서 부른다.
// - PENDING 충전(주문만 하고 결제창에 있음)은 막지 않는다. 돈이 아직 움직이지 않았고, 닫힌 뒤 승인하면 PG를 부르기 전에 거절된다.
// - message는 사용자에게 그대로 보이는 문장이다. 금액은 천 단위 쉼표를 붙인다.
@Component
@RequiredArgsConstructor
class WalletWithdrawalBlocks {

    static final String CHARGE_BALANCE_REMAINING = "BILLING_CHARGE_BALANCE_REMAINING";
    static final String REVENUE_BALANCE_REMAINING = "BILLING_REVENUE_BALANCE_REMAINING";
    static final String REFUND_IN_PROGRESS = "BILLING_REFUND_IN_PROGRESS";
    static final String EXCHANGE_IN_PROGRESS = "BILLING_EXCHANGE_IN_PROGRESS";
    static final String CHARGE_IN_PROGRESS = "BILLING_CHARGE_IN_PROGRESS";

    // 충전 HP 보류가 남아 있거나 PG 취소 결과를 확인 중인 환불 요청
    static final Set<PointRefundRequestStatus> REFUND_IN_PROGRESS_STATUSES = EnumSet.of(
            PointRefundRequestStatus.REQUESTED, PointRefundRequestStatus.PROCESSING,
            PointRefundRequestStatus.MANUAL_REVIEW_REQUIRED);
    // 수익 HP 보류가 남아 있는 환전 신청(관리자 확인 중인 FAILED·UNKNOWN 포함)
    static final Set<ExchangeStatus> EXCHANGE_IN_PROGRESS_STATUSES = EnumSet.of(ExchangeStatus.REQUESTED,
            ExchangeStatus.APPROVED, ExchangeStatus.PROCESSING, ExchangeStatus.FAILED, ExchangeStatus.UNKNOWN);
    // 정산 예정일을 기다리면 끝나는 환전 상태. 이 상태만 있을 때 availableAt을 준다
    private static final Set<ExchangeStatus> EXCHANGE_SCHEDULED_STATUSES = EnumSet.of(ExchangeStatus.REQUESTED,
            ExchangeStatus.APPROVED);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PointRefundRequestRepository pointRefundRequestRepository;
    private final ExchangeRepository exchangeRepository;
    private final PointChargeRepository pointChargeRepository;

    // 막힌 이유를 정해진 순서(충전 잔액, 수익 잔액, 환불, 환전, 충전 확인 중)로 돌려준다. 없으면 빈 목록이다.
    List<WithdrawalBlockReasonDto> of(Wallet wallet) {
        List<WithdrawalBlockReasonDto> reasons = new ArrayList<>();
        if (wallet.getChargeBalanceHp() > 0) {
            reasons.add(WithdrawalBlockReasonDto.of(CHARGE_BALANCE_REMAINING,
                    "충전 HP %s이 남아 있어요. 환불한 뒤 탈퇴할 수 있어요.".formatted(amount(wallet.getChargeBalanceHp()))));
        }
        if (wallet.getRevenueBalanceHp() > 0) {
            reasons.add(WithdrawalBlockReasonDto.of(REVENUE_BALANCE_REMAINING,
                    "수익 HP %s이 남아 있어요. 환전을 신청한 뒤 탈퇴할 수 있어요.".formatted(
                            amount(wallet.getRevenueBalanceHp()))));
        }
        if (pointRefundRequestRepository.existsByWalletIdAndStatusIn(wallet.getId(), REFUND_IN_PROGRESS_STATUSES)) {
            reasons.add(WithdrawalBlockReasonDto.of(REFUND_IN_PROGRESS,
                    "처리 중인 환불이 있어요. 환불이 끝난 뒤 탈퇴할 수 있어요."));
        }
        List<Exchange> exchanges = exchangeRepository.findAllByWalletIdAndStatusIn(wallet.getId(),
                EXCHANGE_IN_PROGRESS_STATUSES);
        if (!exchanges.isEmpty()) {
            reasons.add(new WithdrawalBlockReasonDto(EXCHANGE_IN_PROGRESS,
                    "처리 중인 환전이 있어요. 송금이 끝난 뒤 탈퇴할 수 있어요.", exchangeAvailableAt(exchanges)));
        }
        if (pointChargeRepository.existsByWalletIdAndStatus(wallet.getId(), PointChargeStatus.PROCESSING)) {
            reasons.add(WithdrawalBlockReasonDto.of(CHARGE_IN_PROGRESS,
                    "확인 중인 충전이 있어요. 잠시 뒤 다시 시도해 주세요."));
        }
        return List.copyOf(reasons);
    }

    // 모든 환전이 정산일을 기다리는 중(신청·승인)이면 가장 늦은 정산 예정일(KST). 송금 중이거나 관리자 확인 중인 환전이 있으면 알 수 없다(null).
    private static LocalDate exchangeAvailableAt(List<Exchange> exchanges) {
        LocalDate latest = null;
        for (Exchange exchange : exchanges) {
            if (!EXCHANGE_SCHEDULED_STATUSES.contains(exchange.getStatus()) || exchange.getPayoutScheduledAt() == null) {
                return null;
            }
            LocalDate date = exchange.getPayoutScheduledAt().atZone(KST).toLocalDate();
            if (latest == null || date.isAfter(latest)) {
                latest = date;
            }
        }
        return latest;
    }

    private static String amount(long hp) {
        return String.format(Locale.KOREA, "%,d", hp);
    }
}
