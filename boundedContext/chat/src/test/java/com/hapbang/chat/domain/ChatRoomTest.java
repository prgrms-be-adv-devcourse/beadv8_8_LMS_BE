package com.hapbang.chat.domain;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatRoomTest {

    @Test
    void 일대일_채팅방을_만든다() {
        ChatRoom room = directRoom();

        assertThat(room.getRoomType()).isEqualTo(ChatRoomType.DIRECT);
        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
        assertThat(room.getCreatedAt()).isEqualTo(NOW);
        assertThat(room.getLastSequence()).isZero();
    }

    @Test
    void 두_회원_키는_요청_순서와_관계없이_같다() {
        assertThat(ChatRoom.directKeyOf(HOST_ID, GUEST_ID)).isEqualTo(ChatRoom.directKeyOf(GUEST_ID, HOST_ID));
        assertThat(directRoom().getDirectKey()).isEqualTo(HOST_ID + ":" + GUEST_ID);
    }

    @Test
    void 메시지_순번을_차례로_발급한다() {
        ChatRoom room = directRoom();

        assertThat(room.issueSequence()).isEqualTo(1);
        assertThat(room.issueSequence()).isEqualTo(2);
    }

    @Test
    void 채팅방_로그를_남긴다() {
        ChatRoomLog log = ChatRoomLog.record(directRoom(), HOST_ID, ChatRoomEventType.ROOM_CREATED, NOW);

        assertThat(log.getActorUserId()).isEqualTo(HOST_ID);
        assertThat(log.getEventType()).isEqualTo(ChatRoomEventType.ROOM_CREATED);
        assertThat(log.getOccurredAt()).isEqualTo(NOW);
    }
}
