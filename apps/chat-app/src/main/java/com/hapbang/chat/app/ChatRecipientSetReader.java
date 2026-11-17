package com.hapbang.chat.app;

/**
 * 불변 수신자 집합 헤더 조회. 저장은 repository 메서드를 그대로 쓴다.
 */
public interface ChatRecipientSetReader {

    /** 방의 마지막 수신자 집합 버전. 없으면 0. 방을 잠근 트랜잭션에서 다음 버전을 정할 때 쓴다 */
    int findLastVersion(Long chatRoomId);
}
