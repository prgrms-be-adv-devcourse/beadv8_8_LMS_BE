package com.hapbang.chat.app;

import java.util.Optional;
import java.util.OptionalInt;

import com.hapbang.chat.app.dto.ChatMessageResponse;

/**
 * 전달 payload를 만들 원본(메시지·불변 수신자 집합)을 읽는 계약. 자기 chat DB만 읽는다.
 * 원본은 만든 뒤 바뀌지 않으므로 호출마다 따로 읽어도 된다.
 */
public interface ChatDeliverySourceReader {

    /**
     * 메시지 원본.
     *
     * @param message         전송 ID 없는 공용 응답
     * @param clientMessageId 보낸 사람이 붙인 전송 ID. 없으면 null
     */
    record MessageSource(ChatMessageResponse message, Long senderUserId, String clientMessageId) {
    }

    Optional<MessageSource> findMessage(long messageId);

    /** 수신자 집합의 방 안 버전. 집합이 없으면 빈 값 */
    OptionalInt findRecipientSetVersion(long recipientSetId);
}
