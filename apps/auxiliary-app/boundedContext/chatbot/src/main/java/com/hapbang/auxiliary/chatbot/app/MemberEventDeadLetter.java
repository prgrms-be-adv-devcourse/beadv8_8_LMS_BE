package com.hapbang.auxiliary.chatbot.app;

import org.jspecify.annotations.Nullable;

import com.hapbang.auxiliary.chatbot.domain.MemberEventFailure;
import com.hapbang.auxiliary.chatbot.domain.MemberEventType;

/**
 * 재시도를 다 쓰고 DLT로 보내는 회원 이벤트 하나 (#976). Kafka 레코드에서 꺼낸 값이다.
 *
 * @param source 원래 메시지의 위치
 * @param userId 회원 ID. 모르면 null
 * @param type   이벤트 종류
 * @param event  다시 처리할 수 있으면 받은 이벤트, 아니면(읽지 못함·필수 값 없음) null
 * @param cause  처리 실패 예외(리스너 감싸기를 벗긴 것)
 */
public record MemberEventDeadLetter(
        MemberEventFailure.Source source,
        @Nullable Long userId,
        MemberEventType type,
        @Nullable Object event,
        Throwable cause
) {
}
