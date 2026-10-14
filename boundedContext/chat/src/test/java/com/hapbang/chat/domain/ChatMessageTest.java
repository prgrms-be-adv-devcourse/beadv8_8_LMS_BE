package com.hapbang.chat.domain;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ChatMessageTest {

    @Test
    void 텍스트_메시지는_방_순번을_받는다() {
        ChatRoom room = directRoom();

        ChatMessage first = ChatMessage.text(room, HOST_ID, "안녕하세요", NOW);
        ChatMessage second = ChatMessage.text(room, GUEST_ID, "반갑습니다", NOW);

        assertThat(first.getMessageType()).isEqualTo(ChatMessageType.TEXT);
        assertThat(first.getRoomSequence()).isEqualTo(1);
        assertThat(second.getRoomSequence()).isEqualTo(2);
        assertThat(first.isDeleted()).isFalse();
    }

    @Test
    void 퇴장_안내_시스템_메시지를_만든다() {
        ChatMessage message = ChatMessage.leftNotice(directRoom(), GUEST_ID, "게스트", NOW);

        assertThat(message.getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(message.getContent()).isEqualTo("게스트님이 나갔습니다.");
        assertThat(message.getSenderUserId()).isEqualTo(GUEST_ID);
        assertThat(message.getRoomSequence()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void 내용이_비어_있으면_보낼_수_없고_순번도_쓰지_않는다(String content) {
        ChatRoom room = directRoom();

        assertThatThrownBy(() -> ChatMessage.text(room, HOST_ID, content, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("메시지 내용이 비어 있습니다.");
        assertThat(room.getLastSequence()).isZero();
    }

    @Test
    void 재입장_안내는_재입장한_회원이_보낸_시스템_메시지다() {
        ChatMessage notice = ChatMessage.rejoinNotice(directRoom(), GUEST_ID, "게스트", NOW);

        assertThat(notice.getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(notice.getSenderUserId()).isEqualTo(GUEST_ID);
        assertThat(notice.getContent()).isEqualTo("게스트님이 다시 들어왔습니다.");
        assertThat(notice.getRoomSequence()).isEqualTo(1);
    }
}
