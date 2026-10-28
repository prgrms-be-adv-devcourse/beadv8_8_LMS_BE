package com.hapbang.billing.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.app.BillingUserSyncUseCase;
import com.hapbang.billing.domain.BillingUser;
import com.hapbang.billing.domain.Wallet;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.testsupport.IntegrationTest;

import jakarta.persistence.EntityManager;

// 회원 복제본 반영(#294)의 실패 격리를 확인한다.
// - 복제본 반영이 실패해도 같은 가입 이벤트의 지갑 생성은 영향을 받지 않는다(리스너가 따로라 Modulith가 따로 기록한다).
// - 동시 생성 PK 충돌 뒤 재시도는 실패한 트랜잭션이 아니라 새 트랜잭션에서 하고, 호출한 쪽 트랜잭션을 rollback-only로 만들지 않는다.
// 실패를 일부러 내는 빈 교체가 있어 이 클래스만 쓰는 컨텍스트다. 연결 풀을 작게 두고 끝나면 닫는다.
@IntegrationTest
@EnableScenarios
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingMemberEventIsolationTest {

    @MockitoSpyBean
    BillingUserSyncUseCase billingUserSyncUseCase;

    @MockitoSpyBean
    BillingUserRepository billingUserRepository;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    EntityManager entityManager;

    @Test
    @DisplayName("복제본 반영이 실패해도 지갑은 만들어진다")
    void replicaFailureDoesNotBlockWallet(Scenario scenario) {
        doThrow(new IllegalStateException("복제본 반영 실패(테스트)")).when(billingUserSyncUseCase).apply(any());

        scenario.publish(new UserJoinedEvent(new UserDto(300L, "민수", Role.INFLUENCER, UserStatus.ACTIVE, 0L)))
                .andWaitForStateChange(() -> walletRepository.findByUserId(300L), Optional::isPresent);

        assertThat(billingUserRepository.findById(300L)).isEmpty();
    }

    @Test
    @DisplayName("PK 충돌 뒤 재시도는 새 트랜잭션에서 하고, 호출한 쪽 트랜잭션에 합류하지 않아 그 트랜잭션은 정상 커밋된다")
    void retryRunsInNewTransaction() {
        AtomicInteger saves = new AtomicInteger();
        List<Object> transactionResources = new ArrayList<>();
        // 저장소 빈은 인터페이스 프록시라 스파이에서 실제 메서드를 부를 수 없다. 같은 엔티티의 실제 저장소를 따로 만들어 위임한다
        BillingUserRepository real = new JpaRepositoryFactory(entityManager).getRepository(BillingUserRepository.class);
        // 첫 저장은 다른 이벤트가 먼저 만든 것처럼 PK 충돌로 실패시키고, 시도마다 묶인 트랜잭션 자원(영속성 컨텍스트)을 기록한다
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            transactionResources.add(TransactionSynchronizationManager.getResourceMap().values().stream()
                    .map(System::identityHashCode).toList());
            if (saves.incrementAndGet() == 1) {
                throw new DataIntegrityViolationException("duplicate key (테스트)");
            }
            return real.saveAndFlush(invocation.getArgument(0));
        }).when(billingUserRepository).saveAndFlush(any(BillingUser.class));

        // 바깥 트랜잭션 안에서 호출해도 바깥 트랜잭션의 쓰기는 커밋돼야 한다
        Boolean outerRollbackOnly = new TransactionTemplate(transactionManager).execute(status -> {
            walletRepository.save(Wallet.create(400L));
            BillingUserSyncUseCase.Outcome outcome = billingUserSyncUseCase.apply(
                    new UserDto(400L, "민수", Role.INFLUENCER, UserStatus.ACTIVE, 0L));
            assertThat(outcome).isEqualTo(BillingUserSyncUseCase.Outcome.CREATED);
            return status.isRollbackOnly();
        });

        assertThat(outerRollbackOnly).isFalse();
        assertThat(saves.get()).isEqualTo(2);
        // 두 시도는 서로 다른 트랜잭션 자원에서 실행됐다
        assertThat(transactionResources).hasSize(2).doesNotHaveDuplicates();
        assertThat(walletRepository.findByUserId(400L)).isPresent();
        assertThat(billingUserRepository.findById(400L)).isPresent();
    }
}
