package com.hapbang.chat.app;

/**
 * {@link ChatRecipientSetRepository}의 QueryDSL 조회. 구현은 out의 {@code ChatRecipientSetRepositoryCustomImpl}이다.
 * 저장은 {@link ChatRecipientSetRepository}에 둔다.
 */
public interface ChatRecipientSetRepositoryCustom {

    /** 방의 마지막 수신자 집합 버전. 없으면 0. 방을 잠근 트랜잭션에서 다음 버전을 정할 때 쓴다 */
    int findLastVersion(Long chatRoomId);
}
