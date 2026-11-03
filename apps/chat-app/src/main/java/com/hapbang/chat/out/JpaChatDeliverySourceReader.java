package com.hapbang.chat.out;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.ChatDeliverySourceReader;
import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatMessageSender;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatReadCountRows.MessageUnreadRow;

import lombok.RequiredArgsConstructor;

/**
 * {@link ChatDeliverySourceReader}의 JPA 구현. 메시지와 불변 수신자 집합 헤더를 짧은 읽기 전용 트랜잭션으로 읽는다.
 * 일반 메시지는 전달 원본을 만들 때 안 읽은 사람 수를 집계 문장 하나로 함께 센다(받는 회원마다가 아니라 메시지마다 한 번).
 */
@Component
@RequiredArgsConstructor
public class JpaChatDeliverySourceReader implements ChatDeliverySourceReader {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatRecipientSetRepository chatRecipientSetRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<MessageSource> findMessage(long messageId) {
        // 발신자 표시 정보는 메시지와 같은 문장으로 읽는다(메시지마다 회원을 따로 조회하지 않는다). 전달 시점의 닉네임이다
        return chatMessageRepository.findWithSender(messageId).stream().findFirst()
                .map(row -> {
                    ChatMessage message = (ChatMessage) row[0];
                    ChatUser sender = (ChatUser) row[1];
                    ChatMessageResponse response = ChatMessageResponse.from(message).withSender(
                            ChatMessageSender.applies(message) ? ChatMessageSender.of(message.getSenderUserId(), sender)
                                    : null);
                    return new MessageSource(withUnreadMemberCount(response), message.getSenderUserId(),
                            message.getClientMessageId());
                });
    }

    /** 일반 메시지면 전달 시점의 안 읽은 사람 수와 버전을 담는다. 시스템 안내·알림은 세지 않는다 */
    private ChatMessageResponse withUnreadMemberCount(ChatMessageResponse response) {
        if (response.messageType() != ChatMessageType.TEXT) {
            return response;
        }
        List<MessageUnreadRow> rows = chatMessageRepository.countUnreadMembers(List.of(response.messageId()));
        if (rows.isEmpty()) {
            return response;
        }
        MessageUnreadRow row = rows.getFirst();
        return response.withReadCount(Math.toIntExact(row.getUnreadMemberCount()), row.getReadCountVersion());
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalInt findRecipientSetVersion(long recipientSetId) {
        return chatRecipientSetRepository.findById(recipientSetId)
                .map(recipientSet -> OptionalInt.of(recipientSet.getSetVersion()))
                .orElse(OptionalInt.empty());
    }
}
