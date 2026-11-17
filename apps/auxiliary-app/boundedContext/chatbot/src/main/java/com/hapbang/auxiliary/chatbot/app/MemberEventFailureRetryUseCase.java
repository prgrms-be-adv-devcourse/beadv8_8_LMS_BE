package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.chatbot.app.dto.MemberEventRetryResDto;
import com.hapbang.auxiliary.chatbot.app.dto.MemberEventRetryResult;
import com.hapbang.auxiliary.chatbot.config.ChatbotConfig;
import com.hapbang.auxiliary.chatbot.config.ChatbotMemberEventFailureProperties;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailure;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailureStatus;
import com.hapbang.auxiliary.chatbot.out.MaskedMemberStore;
import com.hapbang.auxiliary.chatbot.out.MemberEventFailureRepository;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserMaskedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * DLT로 보낸 회원 이벤트를 관리자가 다시 처리한다 (#976). Kafka 소비와 같은 적용기·마스킹을 부른다.
 * <ul>
 *     <li>건마다 트랜잭션 하나: 그 기록의 회원 락을 잡고, 실패 행을 잠근 뒤 반영하고 처리됨으로 바꾼다.
 *     락 순서는 마스킹({@link MemberMasking})과 같은 "회원 → 실패 행"이다(반대면 마스킹이 같은 회원의 실패 원문을 비울 때 교착된다).
 *     같은 회원을 다루는 Kafka 소비·마스킹·다른 관리자 요청은 회원 락에서 기다린다. 뒤에 온 같은 건은 {@code ALREADY_RESOLVED}다.</li>
 *     <li>두 번 처리해도 결과가 같다: 마스킹은 멱등, 가입·변경은 version 비교로 옛 값이 새 값을 덮지 않는다.</li>
 *     <li>반영이 실패하면 그 트랜잭션은 롤백하고, 새 트랜잭션에서 횟수와 이유만 바꾼다.</li>
 *     <li>고르지 않으면 다시 처리할 수 있는 처리 대기 기록을 오래된 순으로 상한({@code retry-all-limit})만큼 처리한다.
 *     다시 처리할 수 없는 기록은 처리 대기로 남아 목록에서 보이지만, 전체 처리의 앞자리를 막지 않게 뺀다.</li>
 * </ul>
 */
@Slf4j
@Service
public class MemberEventFailureRetryUseCase {

    private final MemberEventFailureRepository repository;
    private final MaskedMemberStore maskedMemberStore;
    private final CopyApplier copyApplier;
    private final MemberMasking memberMasking;
    private final ChatbotMemberEventFailureProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MemberEventFailureRetryUseCase(MemberEventFailureRepository repository, MaskedMemberStore maskedMemberStore,
            CopyApplier copyApplier, MemberMasking memberMasking, ChatbotMemberEventFailureProperties properties,
            PlatformTransactionManager transactionManager, @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.repository = repository;
        this.maskedMemberStore = maskedMemberStore;
        this.copyApplier = copyApplier;
        this.memberMasking = memberMasking;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param ids     다시 처리할 기록. 비어 있으면 다시 처리할 수 있는 처리 대기 기록(오래된 순, 상한까지)
     * @param adminId 요청한 관리자
     * @return 건별 결과(요청 순서)
     */
    public List<MemberEventRetryResDto> retry(List<Long> ids, Long adminId) {
        List<Long> targets = ids == null || ids.isEmpty()
                ? repository.findReplayableIds(MemberEventFailureStatus.PENDING, PageRequest.of(0, properties.retryAllLimit()))
                : ids;
        List<MemberEventRetryResDto> results = new ArrayList<>();
        for (Long id : targets) {
            MemberEventRetryResDto result = retryOne(id, adminId);
            log.info("관리자가 회원 이벤트를 다시 처리했다 failureId={}, adminId={}, result={}", id, adminId, result.result());
            results.add(result);
        }
        return results;
    }

    private MemberEventRetryResDto retryOne(long id, Long adminId) {
        try {
            MemberEventRetryResult result = transaction.execute(status -> replayLocked(id, adminId));
            return MemberEventRetryResDto.of(id, result);
        } catch (RuntimeException failure) {
            MemberEventFailure.Cause cause = FailureCauseExtractor.extract(failure);
            recordRetryFailure(id, cause);
            return new MemberEventRetryResDto(id, MemberEventRetryResult.FAILED, cause.exceptionClass(), cause.message());
        }
    }

    private MemberEventRetryResult replayLocked(long id, Long adminId) {
        List<Long> owner = repository.findUserIdById(id);
        if (owner.isEmpty()) {
            return MemberEventRetryResult.NOT_FOUND;
        }
        Long userId = owner.getFirst();
        if (userId != null) {
            maskedMemberStore.lock(userId);
        }
        Optional<MemberEventFailure> locked = repository.lockById(id);
        if (locked.isEmpty()) {
            return MemberEventRetryResult.NOT_FOUND;
        }
        MemberEventFailure failure = locked.get();
        if (failure.getStatus() == MemberEventFailureStatus.RESOLVED) {
            return MemberEventRetryResult.ALREADY_RESOLVED;
        }
        if (!failure.isReplayable()) {
            return MemberEventRetryResult.NOT_REPLAYABLE;
        }
        replay(MemberEventPayload.read(failure.getEventType(), failure.getPayload()));
        failure.resolve(adminId, CopyTimes.now(clock));
        return MemberEventRetryResult.SUCCEEDED;
    }

    private void replay(Object event) {
        switch (event) {
            case UserJoinedEvent joined -> copyApplier.apply(joined.user());
            case UserModifiedEvent modified -> copyApplier.apply(modified.user());
            case UserMaskedEvent masked -> memberMasking.mask(masked);
            default -> throw new IllegalStateException("다시 처리할 수 없는 회원 이벤트입니다: " + event.getClass().getName());
        }
    }

    // 반영 실패와 기록 실패를 나눈다. 기록이 실패해도 응답은 반영 실패(FAILED)다
    private void recordRetryFailure(long id, MemberEventFailure.Cause cause) {
        try {
            transaction.executeWithoutResult(status -> {
                Optional<MemberEventFailure> failure = repository.findById(id);
                if (failure.isPresent()) {
                    failure.get().retryFailed(cause, CopyTimes.now(clock));
                }
            });
        } catch (RuntimeException recordFailure) {
            log.warn("회원 이벤트 다시 처리 실패를 기록하지 못했다 failureId={}, exceptionClass={}", id,
                    recordFailure.getClass().getName());
        }
        log.warn("회원 이벤트를 다시 처리했지만 또 실패했다 failureId={}, exceptionClass={}, rootCauseClass={}", id,
                cause.exceptionClass(), cause.rootCauseClass());
    }
}
