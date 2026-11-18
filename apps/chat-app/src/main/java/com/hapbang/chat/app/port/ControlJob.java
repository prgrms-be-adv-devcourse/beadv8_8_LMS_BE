package com.hapbang.chat.app.port;

import java.time.Instant;
import java.util.UUID;

import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatControlType;

/**
 * 연결 제어 outbox({@code chat_control_outbox}) 행.
 *
 * @param loggedOutAt 로그아웃 작업의 기준 시각. 재검사 작업과 이 값이 생기기 전의 로그아웃 작업은 null
 */
public record ControlJob(long id, UUID jobId, ChatControlType type, long userId, Long memberVersion,
        ChatControlReason reason, Instant loggedOutAt, int schemaVersion) implements OutboxJob {
}
