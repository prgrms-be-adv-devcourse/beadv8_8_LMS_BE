package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessage;

/**
 * 메시지를 보낸다. 저장(방 잠금·순번 발급·전달 outbox 기록)은 메서드마다 하나의 쓰기 트랜잭션이다.
 * 같은 참여의 같은 전송 ID 재전송은 새로 저장하지 않고 기존 메시지를 돌려준다({@code resent=true}).
 */
public interface ChatMessageService {

    /**
     * 본문 검증. DB를 쓰지 않으므로 Facade가 저장 트랜잭션을 열기 전에 먼저 부른다(설계 4.6 "DB 트랜잭션 전").
     * 구현을 직접 쓰는 호출도 같은 검사를 받도록 {@link #send}에서도 한 번 더 부른다.
     */
    static void validateContent(String content) {
        if (!ChatMessage.hasContent(content)) {
            throw new ChatException(ChatErrorCode.EMPTY_MESSAGE);
        }
        if (!ChatMessage.fitsLength(content)) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST,
                    "메시지는 " + ChatMessage.MAX_CONTENT_LENGTH + "자 이하여야 합니다.");
        }
    }

    /**
     * 메시지를 보낸다. 역할은 채팅 회원의 복제된 역할로 검사한다.
     *
     * @param clientMessageId 보낸 사람이 붙인 전송 ID. 없으면 중복을 확인하지 않는다
     */
    SentChatMessage send(Long chatRoomId, Long senderId, String content, String clientMessageId);
}
