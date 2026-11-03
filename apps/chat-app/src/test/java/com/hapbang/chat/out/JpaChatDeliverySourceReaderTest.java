package com.hapbang.chat.out;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.app.ChatDeliverySourceReader;
import com.hapbang.chat.app.ChatDeliverySourceReader.MessageSource;
import com.hapbang.chat.app.ChatMessageUseCase;
import com.hapbang.chat.app.ChatRoomUseCase;
import com.hapbang.chat.app.ChatUserUseCase;
import com.hapbang.chat.app.dto.ChatMessageSender;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.testsupport.IntegrationTest;

// 실시간 전달 원본은 메시지와 발신자 복제본을 한 문장으로 읽어 발신자 표시 정보를 싣는다
@IntegrationTest
class JpaChatDeliverySourceReaderTest {

    @Autowired
    private ChatDeliverySourceReader reader;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
    }

    @Test
    void 일반_메시지의_전달_원본은_발신자_닉네임을_싣는다() {
        long messageId = chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", null).message().messageId();

        assertThat(reader.findMessage(messageId)).get().extracting(source -> source.message().sender())
                .isEqualTo(new ChatMessageSender(HOST_ID, "호스트", false));
    }

    @Test
    void 시스템_안내의_전달_원본은_발신자_정보가_없고_없는_메시지는_빈_값이다() {
        Long noticeId = chatRoomUseCase.leave(chatRoomId, GUEST_ID, null).leftNotice().messageId();

        assertThat(reader.findMessage(noticeId)).get().extracting(MessageSource::message)
                .satisfies(message -> assertThat(message.sender()).isNull());
        assertThat(reader.findMessage(Long.MAX_VALUE)).isEmpty();
    }
}
