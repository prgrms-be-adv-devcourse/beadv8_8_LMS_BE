package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ReadChangedFanout;

/**
 * 안 읽은 사람 수가 바뀌었을 수 있다는 신호를 늘 구독하는 서버 채널({@code chat:read:v1})로 낸다. outbox를 거치지 않는 최선 노력이다.
 * DB 트랜잭션 밖(커밋 뒤)에서 부른다. 실패는 예외로 알린다. 계약: {@code apps/chat-app/docs/contracts/unread-count.md}
 */
public interface ChatReadCountPublisher {

    void publish(ReadChangedFanout fanout);
}
