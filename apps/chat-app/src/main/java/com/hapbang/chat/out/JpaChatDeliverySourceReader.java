package com.hapbang.chat.out;

import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.ChatDeliverySourceReader;
import com.hapbang.chat.app.dto.ChatMessageResponse;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatDeliverySourceReader}의 JPA 구현. 메시지와 불변 수신자 집합 헤더를 짧은 읽기 전용 트랜잭션으로 읽는다.
 */
@Component
@RequiredArgsConstructor
public class JpaChatDeliverySourceReader implements ChatDeliverySourceReader {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatRecipientSetRepository chatRecipientSetRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<MessageSource> findMessage(long messageId) {
        return chatMessageRepository.findById(messageId)
                .map(message -> new MessageSource(ChatMessageResponse.from(message), message.getSenderUserId(),
                        message.getClientMessageId()));
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalInt findRecipientSetVersion(long recipientSetId) {
        return chatRecipientSetRepository.findById(recipientSetId)
                .map(recipientSet -> OptionalInt.of(recipientSet.getSetVersion()))
                .orElse(OptionalInt.empty());
    }
}
