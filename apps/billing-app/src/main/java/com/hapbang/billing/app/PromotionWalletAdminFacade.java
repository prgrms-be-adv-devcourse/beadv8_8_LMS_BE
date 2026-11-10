package com.hapbang.billing.app;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 관리자 프로모션 지갑 조회·충전·회수 진입점. 관리자 API는 두 겹으로 막는다: global SecurityConfig의 /api/v1/*/admin/**(ADMIN)과
// 이 Facade의 @PreAuthorize. 트랜잭션은 UseCase가 열고 닫으므로, 이 Facade의 결정 로그는 커밋이 끝난 뒤에 찍힌다.
@Service
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class PromotionWalletAdminFacade {

    private final PromotionWalletQueryUseCase promotionWalletQueryUseCase;
    private final PromotionFundingUseCase promotionFundingUseCase;
    private final PromotionWithdrawalUseCase promotionWithdrawalUseCase;

    public PromotionWalletResult get() {
        return promotionWalletQueryUseCase.get();
    }

    public PromotionFundingResult fund(Long adminUserId, PromotionFundingCommand command) {
        PromotionFundingResult result = LockTimeouts.translate(() -> promotionFundingUseCase.fund(adminUserId, command));
        // 근거 번호·메모는 관리자가 쓴 본문이라 남기지 않는다
        if (result.replayed()) {
            log.info("같은 프로모션 충전 작업을 다시 받아 처음 결과를 돌려줬다 fundingWorkId={}, fundingId={}, adminUserId={}",
                    result.fundingWorkId(), result.fundingId(), adminUserId);
        } else {
            log.info("프로모션 지갑을 충전했다 fundingWorkId={}, fundingId={}, adminUserId={}, sourceType={}, amountHp={}, "
                            + "availableHp={}", result.fundingWorkId(), result.fundingId(), adminUserId,
                    command.sourceType(), result.amountHp(), result.availableHp());
        }
        return result;
    }

    public PromotionWithdrawalResult withdraw(Long adminUserId, PromotionWithdrawalCommand command) {
        PromotionWithdrawalResult result = LockTimeouts.translate(
                () -> promotionWithdrawalUseCase.withdraw(adminUserId, command));
        // 근거 번호·메모는 관리자가 쓴 본문이라 남기지 않는다
        if (result.replayed()) {
            log.info("같은 프로모션 회수 작업을 다시 받아 처음 결과를 돌려줬다 withdrawalWorkId={}, withdrawalId={}, adminUserId={}",
                    result.withdrawalWorkId(), result.withdrawalId(), adminUserId);
        } else {
            log.info("프로모션 지갑에서 회수했다 withdrawalWorkId={}, withdrawalId={}, adminUserId={}, amountHp={}, "
                            + "availableHp={}", result.withdrawalWorkId(), result.withdrawalId(), adminUserId,
                    result.amountHp(), result.availableHp());
        }
        return result;
    }
}
