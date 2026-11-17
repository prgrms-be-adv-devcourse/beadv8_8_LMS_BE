package com.hapbang.auxiliary.chatbot.in.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.chatbot.app.MemberEventDeadLetter;
import com.hapbang.auxiliary.chatbot.app.MemberEventFailureRecordUseCase;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailure;
import com.hapbang.auxiliary.chatbot.domain.MemberEventType;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserMaskedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 회원 그룹이 DLT로 보내기 직전에 레코드에서 실패 기록에 남길 값을 꺼낸다 (#976). 회원 그룹의 오류 처리기만 부른다.
 * <ul>
 *     <li>회원 ID: 이벤트 값, 없으면 key(회원 ID, kafka.md 토픽 표). key도 숫자가 아니면 null.</li>
 *     <li>다시 처리할 수 있는 것: 읽은 가입·변경·마스킹 이벤트이고, 실패가 필수 값 없음(오류 처리기가 다시 시도하지 않는
 *     {@link ChatbotKafkaSettings#NOT_RETRYABLE})이 아닌 것. 나머지는 원문 없이 기록만 한다.</li>
 *     <li>예외는 리스너 감싸기({@link ListenerExecutionFailedException})를 벗겨 넘긴다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MemberEventDeadLetterRecorder {

    private final MemberEventFailureRecordUseCase memberEventFailureRecordUseCase;

    public void record(ConsumerRecord<?, ?> record, Exception exception) {
        Throwable cause = unwrap(exception);
        Object value = record.value();
        MemberEventType type = typeOf(value);
        Long userId = userIdOf(value);
        if (userId == null) {
            userId = parseKey(record.key());
        }
        boolean replayable = type != MemberEventType.UNREADABLE && !notRetryable(cause);
        memberEventFailureRecordUseCase.record(new MemberEventDeadLetter(
                new MemberEventFailure.Source(record.topic(), record.partition(), record.offset()),
                userId, type, replayable ? value : null, cause));
    }

    private static boolean notRetryable(Throwable cause) {
        for (Class<? extends Exception> type : ChatbotKafkaSettings.NOT_RETRYABLE) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable unwrap(Exception exception) {
        Throwable current = exception;
        while (current instanceof ListenerExecutionFailedException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // 챗봇이 건너뛰는 다른 타입(로그아웃 등)은 실패하지 않는다. 값이 없거나 그 밖이면 읽지 못한 메시지로 본다
    private static MemberEventType typeOf(Object value) {
        return switch (value) {
            case UserJoinedEvent ignored -> MemberEventType.JOINED;
            case UserModifiedEvent ignored -> MemberEventType.MODIFIED;
            case UserMaskedEvent ignored -> MemberEventType.MASKED;
            case null, default -> MemberEventType.UNREADABLE;
        };
    }

    private static Long userIdOf(Object value) {
        return switch (value) {
            case UserJoinedEvent joined -> idOf(joined.user());
            case UserModifiedEvent modified -> idOf(modified.user());
            case UserMaskedEvent masked -> masked.userId();
            case null, default -> null;
        };
    }

    private static Long idOf(UserDto user) {
        return user == null ? null : user.id();
    }

    private static Long parseKey(Object key) {
        if (key == null) {
            return null;
        }
        try {
            return Long.valueOf(key.toString());
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }
}
