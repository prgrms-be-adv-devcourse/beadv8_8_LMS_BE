package com.hapbang.billing.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.hapbang.billing.global.scheduling.BillingJob;
import com.hapbang.billing.global.scheduling.FakeAdvisoryLocks;
import com.hapbang.billing.global.scheduling.JobLock;

// 스케줄 배치 5개가 실행 락(#598, BD-138)을 거친다: 다른 pod가 쥐고 있거나 락 오류면 UseCase를 부르지 않고, 잡으면 한 번 부른다.
class BillingBatchJobLockTest {

    /**
     * @param batch        UseCase mock과 락으로 배치를 만들고 그 run()을 돌려준다
     * @param verifyCalled UseCase mock이 한 번 불렸는지 확인한다
     */
    record Case(String name, BillingJob job, Class<?> useCaseType, BiFunction<Object, JobLock, Runnable> batch,
            Consumer<Object> verifyCalled) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> batches() {
        return Stream.of(
                new Case("ChargeRecoveryBatch", BillingJob.CHARGE_RECOVERY, ChargeRecoveryUseCase.class,
                        (useCase, lock) -> new ChargeRecoveryBatch((ChargeRecoveryUseCase) useCase, lock)::run,
                        useCase -> verify((ChargeRecoveryUseCase) useCase).recoverStale(any())),
                new Case("RefundRecoveryBatch", BillingJob.REFUND_RECOVERY, RefundRecoveryUseCase.class,
                        (useCase, lock) -> new RefundRecoveryBatch((RefundRecoveryUseCase) useCase, lock)::run,
                        useCase -> verify((RefundRecoveryUseCase) useCase).recoverStale(any())),
                new Case("ExchangePayoutRecoveryBatch", BillingJob.EXCHANGE_PAYOUT_RECOVERY, ExchangePayoutUseCase.class,
                        (useCase, lock) -> new ExchangePayoutRecoveryBatch((ExchangePayoutUseCase) useCase, lock)::run,
                        useCase -> verify((ExchangePayoutUseCase) useCase).recoverStale()),
                new Case("ExchangeSettlementBatch", BillingJob.EXCHANGE_SETTLEMENT, ExchangeSettlementUseCase.class,
                        (useCase, lock) -> new ExchangeSettlementBatch((ExchangeSettlementUseCase) useCase, lock)::run,
                        useCase -> verify((ExchangeSettlementUseCase) useCase).run()),
                new Case("BillingEventRepublishBatch", BillingJob.EVENT_REPUBLISH, BillingEventRepublishUseCase.class,
                        (useCase, lock) -> new BillingEventRepublishBatch((BillingEventRepublishUseCase) useCase, lock)::run,
                        useCase -> verify((BillingEventRepublishUseCase) useCase).republishDue(any())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("batches")
    void 다른_pod가_쥐고_있으면_실행하지_않고_풀리면_한_번_실행한다(Case batchCase) {
        FakeAdvisoryLocks db = new FakeAdvisoryLocks();
        Object useCase = mock(batchCase.useCaseType());
        Runnable batch = batchCase.batch().apply(useCase, db.pod());

        try (JobLock.Lease ignored = db.pod().tryAcquire(batchCase.job()).orElseThrow()) {
            batch.run();
            verifyNoInteractions(useCase);
        }

        batch.run();
        batchCase.verifyCalled().accept(useCase);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("batches")
    void 락을_시도하지_못하면_실행하지_않는다(Case batchCase) {
        FakeAdvisoryLocks db = new FakeAdvisoryLocks();
        Object useCase = mock(batchCase.useCaseType());
        Runnable batch = batchCase.batch().apply(useCase, db.pod());
        db.failing(true);

        batch.run();

        verifyNoInteractions(useCase);
    }
}
