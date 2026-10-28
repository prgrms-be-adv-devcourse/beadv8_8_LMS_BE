package com.hapbang.billing.app;

import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.billing.domain.BillingUser;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.extern.slf4j.Slf4j;

// 회원 가입·변경 이벤트를 Billing 회원 복제본에 반영한다 (#294). 두 이벤트 모두 같은 Upsert 규칙이다:
// - 복제본이 없으면 만든다(가입보다 변경 이벤트가 먼저 와도 만든다)
// - 있으면 shared ReplicaUser.syncFrom으로 갱신한다: 이미 반영한 회원 버전보다 큰 버전만 반영하고 같거나 낮은 버전은 무시한다
//   (버전이 건너뛰어도 크면 반영). 그래서 이벤트 처리 순서가 바뀌어도 최종적으로 가장 높은 버전이 남는다.
// - 버전 없는 정보(UserDto.version == null)는 반영하지 않는다(최신 정보로 취급하지 않는다). 회원 모듈은 #292부터 항상 버전을 담는다.
// 같은 회원의 이벤트가 동시에 처리되면: 갱신은 복제본 행을 잠가(PESSIMISTIC_WRITE) 읽고 버전을 비교하므로 차례로 처리된다.
// 동시에 처음 만들 때만 PK 충돌이 나고(복제본 낙관적 락 충돌도 같이 대비), 짧은 트랜잭션으로 다시 잠가 읽어 버전 비교부터 다시 한다.
// 정해진 횟수 안에 끝나지 않으면 예외를 던져 이벤트 발행 기록에 미완료로 남긴다(재시작 시 다시 처리).
@Slf4j
@Service
public class BillingUserSyncUseCase {

    static final int MAX_ATTEMPTS = 3;

    private final BillingUserRepository billingUserRepository;
    private final TransactionTemplate transactionTemplate;

    public BillingUserSyncUseCase(BillingUserRepository billingUserRepository,
            PlatformTransactionManager transactionManager) {
        this.billingUserRepository = billingUserRepository;
        // 시도마다 독립된 새 트랜잭션: 호출한 쪽에 트랜잭션이 있어도 합류하지 않는다. PK 충돌로 실패한 시도는 그 트랜잭션만
        // 롤백되고(바깥 트랜잭션을 rollback-only로 만들지 않는다), 다음 시도는 새 트랜잭션·새 영속성 컨텍스트에서 다시 읽는다.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public enum Outcome {
        /** 복제본을 새로 만들었다 */
        CREATED,
        /** 더 높은 버전이라 반영했다 */
        UPDATED,
        /** 같거나 낮은 버전이라 무시했다 */
        IGNORED_STALE,
        /** 버전 없는 정보라 무시했다 */
        IGNORED_VERSIONLESS
    }

    public Outcome apply(UserDto user) {
        if (user.version() == null) {
            log.warn("버전 없는 회원 정보라 Billing 회원 복제본에 반영하지 않는다 userId={}", user.id());
            return Outcome.IGNORED_VERSIONLESS;
        }
        RuntimeException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return Objects.requireNonNull(transactionTemplate.execute(status -> upsert(user)));
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException conflict) {
                // 같은 회원을 다른 이벤트가 동시에 만들거나 고쳤다. 다시 읽어 버전 비교부터 다시 한다
                lastConflict = conflict;
                log.info("Billing 회원 복제본 동시 반영 충돌, 다시 시도 userId={}, attempt={}", user.id(), attempt);
            }
        }
        throw lastConflict;
    }

    private Outcome upsert(UserDto user) {
        return billingUserRepository.findByIdForUpdate(user.id())
                .map(replica -> {
                    Long before = replica.getMemberVersion();
                    replica.syncFrom(user);
                    if (Objects.equals(before, replica.getMemberVersion())) {
                        return Outcome.IGNORED_STALE;
                    }
                    billingUserRepository.saveAndFlush(replica);
                    return Outcome.UPDATED;
                })
                .orElseGet(() -> {
                    billingUserRepository.saveAndFlush(BillingUser.from(user));
                    return Outcome.CREATED;
                });
    }
}
