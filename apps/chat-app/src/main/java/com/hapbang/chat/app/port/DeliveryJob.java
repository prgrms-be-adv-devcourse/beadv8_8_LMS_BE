package com.hapbang.chat.app.port;

import java.util.UUID;

import com.hapbang.chat.domain.ChatDeliveryType;
import com.hapbang.chat.domain.ChatNotifyRule;

/** 메시지·시스템 안내 전달 outbox({@code chat_outbox}) 행 */
public record DeliveryJob(long id, UUID jobId, ChatDeliveryType type, long messageId, long roomId,
        long roomSequence, long recipientSetId, ChatNotifyRule notifyRule, int schemaVersion) implements OutboxJob {
}
