package com.hapbang.chat.out;

import java.util.List;

import com.hapbang.chat.app.ChatMessageReader;

/** {@link ChatMessageRepository}의 QueryDSL 조회. 구현은 {@link ChatMessageRepositoryCustomImpl} */
public interface ChatMessageRepositoryCustom extends ChatMessageReader {

    /**
     * 메시지와 발신자 복제본을 한 문장으로 읽는다(전달 원본). 발신자가 없거나 복제본이 없으면 둘째 값이 null이다.
     * 결과는 [ChatMessage, ChatUser] 한 행이고, 메시지가 없으면 빈 목록이다. out의 전달 원본 조회
     * ({@link JpaChatDeliverySourceReader})만 쓰므로 app 인터페이스에 두지 않는다.
     */
    List<Object[]> findWithSender(long messageId);
}
