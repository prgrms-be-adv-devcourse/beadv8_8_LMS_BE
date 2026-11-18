package com.hapbang.chat.app;

import com.hapbang.chat.app.port.RoomChannelSubscriptions;

/** 방 채널 구독을 언제나 받는 테스트 대역. 방 채널 상한·Redis 구독을 보지 않는 단위 테스트가 방 구독 목록을 만들 때 쓴다 */
final class AcceptingRoomChannels implements RoomChannelSubscriptions {

    @Override
    public boolean acquire(long roomId) {
        return true;
    }

    @Override
    public void release(long roomId) {
    }
}
