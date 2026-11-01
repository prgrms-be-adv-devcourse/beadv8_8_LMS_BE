package com.hapbang.chat.app;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatNotifyRule;
import com.hapbang.chat.domain.ChatOutbox;
import com.hapbang.chat.domain.ChatPublishCheckpoint;
import com.hapbang.chat.out.ChatOutboxRepository;
import com.hapbang.chat.out.ChatPublishCheckpointRepository;

import lombok.RequiredArgsConstructor;

/**
 * 저장한 메시지의 전달 작업을 남긴다 (설계 4.5). 전달 outbox와 전달 그룹의 초기 발행 체크포인트를
 * 메시지 저장과 같은 트랜잭션에 쓴다(MANDATORY). 롤백하면 작업도 남지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatDeliveryRecorder {

    private final ChatOutboxRepository chatOutboxRepository;
    private final ChatPublishCheckpointRepository chatPublishCheckpointRepository;

    /**
     * @param recipientSetId 이 메시지의 수신자로 확정한 불변 수신자 집합
     * @return 전달 작업의 안정 ID({@code deliveryId})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordMessage(ChatMessage message, Long recipientSetId, ChatNotifyRule notifyRule,
            LocalDateTime now) {
        ChatOutbox outbox = chatOutboxRepository.save(ChatOutbox.forMessage(message, recipientSetId, notifyRule, now));
        chatPublishCheckpointRepository.save(
                ChatPublishCheckpoint.pending(ChatJobStream.DELIVERY.consumerGroup(), outbox.getDeliveryId(), now));
        return outbox.getDeliveryId();
    }
}
