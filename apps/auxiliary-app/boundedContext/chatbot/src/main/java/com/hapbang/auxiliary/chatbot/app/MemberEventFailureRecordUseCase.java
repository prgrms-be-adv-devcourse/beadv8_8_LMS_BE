package com.hapbang.auxiliary.chatbot.app;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.auxiliary.chatbot.config.ChatbotConfig;
import com.hapbang.auxiliary.chatbot.domain.CopyTimes;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailure;
import com.hapbang.auxiliary.chatbot.out.MaskedMemberStore;
import com.hapbang.auxiliary.chatbot.out.MemberEventFailureRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 재시도를 다 쓴 회원 이벤트를 DLT로 보내기 직전에 실패 기록으로 남긴다 (#976). 관리자가 실패 목록에서 이유를 보고 다시 처리한다.
 * <ul>
 *     <li>기록이 실패하면(DB 장애) 예외를 그대로 던진다. 오류 처리기가 DLT로 넘기지 않고 처음부터 다시 시도하므로 메시지를 잃지 않는다.</li>
 *     <li>같은 메시지 위치를 이미 기록했으면(기록 뒤 DLT 전송이 실패해 다시 온 경우) 더하지 않는다.</li>
 *     <li>이미 마스킹된 회원의 가입·변경이면 원문의 닉네임·분야·자기소개를 비워 적는다. 회원 락 안에서 본다.</li>
 *     <li>ERROR 표식에는 회원 ID·종류·예외 클래스만 남긴다. 예외 메시지와 스택은 남기지 않는다(개인정보가 섞일 수 있다).</li>
 * </ul>
 */
@Slf4j
@Service
public class MemberEventFailureRecordUseCase {

    /** alert 표식: 회원 이벤트를 재시도 끝에 DLT로 보냈다(관리자가 다시 처리해야 한다) */
    static final String DEAD_LETTER_LOG_TAG = "[CHATBOT_MEMBER_EVENT_DEAD_LETTER]";

    private final MemberEventFailureRepository repository;
    private final MaskedMemberStore maskedMemberStore;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MemberEventFailureRecordUseCase(MemberEventFailureRepository repository, MaskedMemberStore maskedMemberStore,
            PlatformTransactionManager transactionManager, @Qualifier(ChatbotConfig.CLOCK) Clock clock) {
        this.repository = repository;
        this.maskedMemberStore = maskedMemberStore;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void record(MemberEventDeadLetter deadLetter) {
        MemberEventFailure.Source source = deadLetter.source();
        MemberEventFailure.Cause cause = FailureCauseExtractor.extract(deadLetter.cause());
        MemberEventFailure saved = transaction.execute(status -> {
            if (repository.existsBySourceTopicAndSourcePartitionAndSourceOffset(source.topic(), source.partition(),
                    source.offset())) {
                return null;
            }
            return repository.save(MemberEventFailure.of(source, deadLetter.userId(), deadLetter.type(),
                    payload(deadLetter), cause, CopyTimes.now(clock)));
        });
        if (saved == null) {
            log.warn("이미 실패 기록이 있는 회원 이벤트라 더하지 않는다 topic={}, partition={}, offset={}", source.topic(),
                    source.partition(), source.offset());
            return;
        }
        log.error("{} 회원 이벤트를 재시도 끝에 DLT로 보냈다. 관리자가 실패 목록에서 다시 처리해야 한다 failureId={}, userId={}, "
                        + "eventType={}, replayable={}, exceptionClass={}, rootCauseClass={}, topic={}, partition={}, offset={}",
                DEAD_LETTER_LOG_TAG, saved.getId(), saved.getUserId(), saved.getEventType(), saved.isReplayable(),
                cause.exceptionClass(), cause.rootCauseClass(), source.topic(), source.partition(), source.offset());
    }

    private String payload(MemberEventDeadLetter deadLetter) {
        if (deadLetter.event() == null) {
            return null;
        }
        String payload = MemberEventPayload.write(deadLetter.event());
        if (deadLetter.userId() == null) {
            return payload;
        }
        maskedMemberStore.lock(deadLetter.userId());
        if (maskedMemberStore.isMasked(deadLetter.userId())) {
            return MemberEventPayload.withoutPersonal(deadLetter.type(), payload);
        }
        return payload;
    }
}
