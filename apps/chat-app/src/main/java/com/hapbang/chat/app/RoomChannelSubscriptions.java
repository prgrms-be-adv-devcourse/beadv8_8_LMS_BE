package com.hapbang.chat.app;

/**
 * 방 채널(Redis Pub/Sub)의 동적 구독. 방 topic을 구독한 로컬 세션이 생기면 그 방 채널을 받고, 없어지면 유예 뒤 놓는다.
 * 방마다 참조 수를 센다. Redis가 없는 실행(실시간 전달을 끈 테스트)에는 빈이 없다.
 */
public interface RoomChannelSubscriptions {

    /**
     * 방 채널 참조를 하나 늘린다. 처음이면 방 채널 구독을 더한다(비동기).
     *
     * @return 앱이 구독하는 방 채널 수 상한을 넘어 더하지 못했으면 false(참조를 늘리지 않았다)
     */
    boolean acquire(long roomId);

    /** 방 채널 참조를 하나 줄인다. 0이 되면 유예 뒤 구독을 놓는다 */
    void release(long roomId);
}
