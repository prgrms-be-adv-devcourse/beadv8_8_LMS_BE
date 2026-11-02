package com.hapbang.billing.app;

import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import com.hapbang.billing.domain.BillingErrorCode;
import com.hapbang.billing.domain.BillingException;
import com.hapbang.billing.domain.BillingUser;
import com.hapbang.billing.out.BillingUserRepository;

import lombok.RequiredArgsConstructor;

// 지갑 회원 확인 (#537, BD-131). 로그인한 역할(토큰, Facade의 @PreAuthorize)과 함께 복제한 회원 상태·역할을 확인한다.
// 발급한 토큰은 회수할 수 없어 정지·강등이 최대 15분 늦게 반영되므로, HP를 받거나 내보내는 신청(충전·환불·환전)은 복제본도 통과해야 한다.
// 조회·취소와 내부 API(보류·반환·지급)는 확인하지 않는다. 복제본이 없으면 WALLET_MEMBER_NOT_FOUND(404), 조건이 맞지 않으면
// WALLET_MEMBER_NOT_ALLOWED(403)다. 트랜잭션을 열지 않고, UseCase보다 먼저 부른다.
@Component
@RequiredArgsConstructor
class WalletMemberCheck {

    private final BillingUserRepository billingUserRepository;

    // HP 충전(주문·승인): 정상 상태의 유저·인플루언서·인증된 인플루언서
    void requireCanCharge(Long userId) {
        require(userId, BillingUser::canCharge);
    }

    // 충전 HP 환불 신청: 정상·정지 상태의 유저·인플루언서·인증된 인플루언서
    void requireCanRefund(Long userId) {
        require(userId, BillingUser::canRefund);
    }

    // 수익 HP 환전 신청: 정상 상태의 인플루언서·인증된 인플루언서
    void requireCanRequestExchange(Long userId) {
        require(userId, BillingUser::canRequestExchange);
    }

    private void require(Long userId, Predicate<BillingUser> allowed) {
        BillingUser member = billingUserRepository.findById(userId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.WALLET_MEMBER_NOT_FOUND));
        if (!allowed.test(member)) {
            throw new BillingException(BillingErrorCode.WALLET_MEMBER_NOT_ALLOWED);
        }
    }
}
