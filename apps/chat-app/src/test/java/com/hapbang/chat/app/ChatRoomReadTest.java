package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatMessageType;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 방 목록의 마지막 메시지·안 읽은 수와 읽음 처리.
 */
@IntegrationTest
class ChatRoomReadTest {

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatReadService chatReadService;

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
    }

    @Test
    void 메시지가_없으면_마지막_메시지는_없고_안_읽은_수는_0이다() {
        assertThat(myRoom(GUEST_ID).lastMessage()).isNull();
        assertThat(myRoom(GUEST_ID).unreadCount()).isZero();
    }

    @Test
    void 상대가_보낸_메시지만_안_읽은_수로_센다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "출연 문의드려요", null);
        chatMessageUseCase.send(chatRoomId, GUEST_ID, "네 말씀하세요", null);

        ChatRoomResponse guestRoom = myRoom(GUEST_ID);
        assertThat(guestRoom.unreadCount()).isEqualTo(2);
        assertThat(guestRoom.lastMessage())
                .extracting(ChatMessageResponse::content, ChatMessageResponse::roomSequence)
                .containsExactly("네 말씀하세요", 3L);
        assertThat(myRoom(HOST_ID).unreadCount()).isEqualTo(1);
    }

    @Test
    void 읽음_처리하면_그_뒤에_온_메시지만_센다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "세 번째", null);

        chatReadService.markRead(chatRoomId, GUEST_ID, 2, null);

        assertThat(myRoom(GUEST_ID).unreadCount()).isEqualTo(1);
    }

    @Test
    void 읽음_처리는_줄어들지_않는다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", null);
        chatReadService.markRead(chatRoomId, GUEST_ID, 2, null);

        chatReadService.markRead(chatRoomId, GUEST_ID, 1, null);

        assertThat(myRoom(GUEST_ID).unreadCount()).isZero();
    }

    @Test
    void 아직_없는_순번까지_읽음_처리하면_마지막_순번까지만_반영된다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatReadService.markRead(chatRoomId, GUEST_ID, 100, null);

        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", null);

        assertThat(myRoom(GUEST_ID).unreadCount()).isEqualTo(1);
    }

    @Test
    void 상대의_퇴장_안내도_안_읽은_수와_마지막_메시지에_들어간다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        ChatRoomResponse hostRoom = myRoom(HOST_ID);
        assertThat(hostRoom.unreadCount()).isEqualTo(1);
        assertThat(hostRoom.lastMessage().messageType()).isEqualTo(ChatMessageType.SYSTEM);
    }

    @Test
    void 재입장하면_재입장_이전_메시지는_마지막_메시지와_안_읽은_수에_들어가지_않는다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "나가기 전", null);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        ChatRoomResponse rejoined = chatRoomSupporter.toResponse(
                chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID).chatRoomId(), GUEST_ID);

        assertThat(rejoined.lastMessage()).isNull();
        assertThat(rejoined.unreadCount()).isZero();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "다시 왔네요", null);
        assertThat(myRoom(GUEST_ID).unreadCount()).isEqualTo(1);
    }

    @Test
    void 참여자가_아니면_읽음_처리할_수_없다() {
        assertThatThrownBy(() -> chatReadService.markRead(chatRoomId, OTHER_INFLUENCER_ID, 1, null))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
    }

    @Test
    void 나간_방은_읽음_처리할_수_없다() {
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        assertThatThrownBy(() -> chatReadService.markRead(chatRoomId, GUEST_ID, 1, null))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
    }

    @Test
    void 현재_참여_세대를_보내면_반영한다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", null);

        chatReadService.markRead(chatRoomId, GUEST_ID, 2, 1);

        assertThat(myRoom(GUEST_ID).unreadCount()).isZero();
    }

    @Test
    void 현재_참여_세대가_아닌_세대를_보내면_STALE_PARTICIPATION이고_바꾸지_않는다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", null);

        assertThatThrownBy(() -> chatReadService.markRead(chatRoomId, GUEST_ID, 2, 2))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.STALE_PARTICIPATION);

        assertThat(myRoom(GUEST_ID).unreadCount()).isEqualTo(2);
    }

    @Test
    void 재입장_뒤_늦게_도착한_옛_세대의_읽음은_세대를_보내면_거절한다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "나가기 전", null);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "다시 왔네요", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "반가워요", null);
        long latest = myRoom(GUEST_ID).lastMessage().roomSequence();

        assertThatThrownBy(() -> chatReadService.markRead(chatRoomId, GUEST_ID, latest, 1))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.STALE_PARTICIPATION);
        assertThat(myRoom(GUEST_ID).unreadCount()).isEqualTo(2);

        chatReadService.markRead(chatRoomId, GUEST_ID, latest, 2);
        assertThat(myRoom(GUEST_ID).unreadCount()).isZero();
    }

    @Test
    void 참여_세대를_보내도_읽음_처리는_줄어들지_않는다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", null);
        chatReadService.markRead(chatRoomId, GUEST_ID, 2, 1);

        chatReadService.markRead(chatRoomId, GUEST_ID, 1, 1);
        chatReadService.markRead(chatRoomId, GUEST_ID, 2, 1);

        assertThat(myRoom(GUEST_ID).unreadCount()).isZero();
        chatMessageUseCase.send(chatRoomId, HOST_ID, "세 번째", null);
        assertThat(myRoom(GUEST_ID).unreadCount()).isEqualTo(1);
    }

    private ChatRoomResponse myRoom(Long userId) {
        return chatRoomSupporter.getMyRooms(userId).stream()
                .filter(room -> room.chatRoomId().equals(chatRoomId))
                .findFirst()
                .orElseThrow();
    }
}
