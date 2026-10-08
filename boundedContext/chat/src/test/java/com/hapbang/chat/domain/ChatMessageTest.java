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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void 내용이_비어_있으면_보낼_수_없고_순번도_쓰지_않는다(String content) {
        ChatRoom room = directRoom();

        assertThatThrownBy(() -> ChatMessage.text(room, HOST_ID, content, NOW))
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(ChatErrorCode.EMPTY_MESSAGE);
        assertThat(room.getLastSequence()).isZero();
    }
}
