package com.hapbang.chat.app;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatMessageSender;
import com.hapbang.chat.app.port.ChatMessageRepository;
import com.hapbang.chat.app.port.ChatReadCountRows.MessageUnreadRow;
import com.hapbang.chat.app.port.ChatRecipientSetRepository;
import com.hapbang.chat.domain.ChatMessage;
import com.hapbang.chat.domain.ChatMessageType;

import lombok.RequiredArgsConstructor;

/**
 * 전달 payload를 만들 원본(메시지·불변 수신자 집합)을 읽는다. 자기 chat DB만 읽는다. 원본은 만든 뒤 바뀌지 않으므로 호출마다
 * 따로 읽어도 된다.
 * <p>
 * 메서드마다 짧은 읽기 전용 트랜잭션으로 읽는다. 부르는 쪽({@link ChatFanoutPayloadLoader})은 트랜잭션 없이 Redis 발행 전에
 * 부르므로, 이 클래스를 따로 빈으로 두어 트랜잭션이 Redis I/O를 덮지 않게 한다. 일반 메시지는 전달 원본을 만들 때 안 읽은
 * 사람 수를 집계 문장 하나로 함께 센다(받는 회원마다가 아니라 메시지마다 한 번).
 */
@Component
@RequiredArgsConstructor
public class ChatDeliverySourceReader {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatRecipientSetRepository chatRecipientSetRepository;

    /**
     * 메시지 원본.
     *
     * @param message         전송 ID 없는 공용 응답
     * @param clientMessageId 보낸 사람이 붙인 전송 ID. 없으면 null
     */
    public record MessageSource(ChatMessageResponse message, Long senderUserId, String clientMessageId) {
    }

    @Transactional(readOnly = true)
    public Optional<MessageSource> findMessage(long messageId) {
        // 발신자 표시 정보는 메시지와 같은 문장으로 읽는다(메시지마다 회원을 따로 조회하지 않는다). 전달 시점의 닉네임이다
        return chatMessageRepository.findWithSender(messageId)
                .map(row -> {
                    ChatMessage message = row.message();
                    ChatMessageResponse response = ChatMessageResponse.from(message).withSender(
                            ChatMessageSender.applies(message)
                                    ? ChatMessageSender.of(message.getSenderUserId(), row.sender())
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

    /** 수신자 집합의 방 안 버전. 집합이 없으면 빈 값 */
    @Transactional(readOnly = true)
    public OptionalInt findRecipientSetVersion(long recipientSetId) {
        return chatRecipientSetRepository.findById(recipientSetId)
                .map(recipientSet -> OptionalInt.of(recipientSet.getSetVersion()))
                .orElse(OptionalInt.empty());
    }
}
