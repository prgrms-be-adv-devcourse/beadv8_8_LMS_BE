package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.app.dto.ChatMessageResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatMessageRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 같은 전송 ID(clientMessageId)로 다시 보낸 메시지는 한 번만 저장된다 (#261).
 */
@IntegrationTest
class ChatMessageResendTest {

    private static final int THREADS = 8;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatMessageSupporter chatMessageSupporter;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
    }

    @Test
    void 같은_전송_ID로_다시_보내면_저장하지_않고_기존_메시지를_보낸_사람에게만_돌려준다() {
        SentChatMessage first = chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "client-1");

        SentChatMessage resent = chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "client-1");

        assertThat(resent.message().messageId()).isEqualTo(first.message().messageId());
        assertThat(resent.message().roomSequence()).isEqualTo(first.message().roomSequence());
        // 재전송은 전달 작업을 만들지 않고, 호출한 쪽이 보낸 사람에게만 다시 보낸다
        assertThat(resent.resent()).isTrue();
        assertThat(first.resent()).isFalse();
        assertThat(chatMessageRepository.count()).isEqualTo(1);
        assertThat(myRoom(GUEST_ID).unreadCount()).isEqualTo(1);
    }

    @Test
    void 같은_전송_ID에_본문이_달라도_기존_메시지를_돌려준다() {
        SentChatMessage first = chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "client-1");

        SentChatMessage resent = chatMessageUseCase.send(chatRoomId, HOST_ID, "고친 내용", "client-1");

        assertThat(resent.message().messageId()).isEqualTo(first.message().messageId());
        assertThat(resent.message().content()).isEqualTo("안녕하세요");
        assertThat(chatMessageRepository.count()).isEqualTo(1);
    }

    // 재전송 방지는 같은 참여 안에서만 적용한다 (#311)
    @Test
    void 나갔다가_재입장한_뒤_옛_전송_ID로_보내면_새_메시지로_저장하고_상대에게_알린다() {
        SentChatMessage beforeLeave = chatMessageUseCase.send(chatRoomId, HOST_ID, "나가기 전", "client-1");
        chatRoomUseCase.leave(chatRoomId, HOST_ID, null);
        chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);

        SentChatMessage afterRejoin = chatMessageUseCase.send(chatRoomId, HOST_ID, "다시 왔어요", "client-1");

        assertThat(afterRejoin.message().messageId()).isNotEqualTo(beforeLeave.message().messageId());
        assertThat(afterRejoin.message().content()).isEqualTo("다시 왔어요");
        assertThat(afterRejoin.resent()).isFalse();
        assertThat(afterRejoin.senderParticipationVersion()).isEqualTo(2);
        assertThat(chatMessageSupporter.getMessages(chatRoomId, HOST_ID, null, 30).messages())
                .extracting(ChatMessageResponse::content, ChatMessageResponse::clientMessageId)
                .containsExactly(tuple("다시 왔어요", "client-1"));
        // 재입장 이전 메시지의 전송 ID는 비워져 유일 인덱스와 겹치지 않는다
        assertThat(chatMessageRepository.findById(beforeLeave.message().messageId()))
                .hasValueSatisfying(message -> assertThat(message.getClientMessageId()).isNull());
    }

    @Test
    void 재입장_뒤에도_같은_참여_안의_재전송은_기존_메시지를_돌려준다() {
        chatRoomUseCase.leave(chatRoomId, HOST_ID, null);
        chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);
        SentChatMessage first = chatMessageUseCase.send(chatRoomId, HOST_ID, "다시 왔어요", "client-1");

        SentChatMessage resent = chatMessageUseCase.send(chatRoomId, HOST_ID, "다시 왔어요", "client-1");

        assertThat(resent.message().messageId()).isEqualTo(first.message().messageId());
        assertThat(resent.resent()).isTrue();
    }

    @Test
    void 상대의_전송_ID는_재입장해도_비우지_않는다() {
        SentChatMessage guestMessage = chatMessageUseCase.send(chatRoomId, GUEST_ID, "게스트", "guest-1");
        chatRoomUseCase.leave(chatRoomId, HOST_ID, null);
        chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID);

        assertThat(chatMessageRepository.findById(guestMessage.message().messageId()))
                .hasValueSatisfying(message -> assertThat(message.getClientMessageId()).isEqualTo("guest-1"));
    }

    @Test
    void 전송_ID가_다르거나_없으면_각각_저장한다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "첫 번째", "client-1");
        chatMessageUseCase.send(chatRoomId, HOST_ID, "두 번째", "client-2");
        chatMessageUseCase.send(chatRoomId, HOST_ID, "세 번째", null);
        chatMessageUseCase.send(chatRoomId, HOST_ID, "네 번째", null);

        assertThat(chatMessageRepository.count()).isEqualTo(4);
    }

    @Test
    void 전송_ID는_보낸_사람마다_따로다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "호스트", "client-1");
        chatMessageUseCase.send(chatRoomId, GUEST_ID, "게스트", "client-1");

        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }

    @Test
    void 상대가_나간_뒤의_재전송도_기존_메시지를_돌려준다() {
        SentChatMessage first = chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "client-1");
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        SentChatMessage resent = chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "client-1");

        assertThat(resent.message().messageId()).isEqualTo(first.message().messageId());
    }

    @Test
    void 동시에_같은_전송_ID로_보내도_한_번만_저장한다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<SentChatMessage>> results = IntStream.range(0, THREADS)
                    .mapToObj(i -> executor.submit((Callable<SentChatMessage>) () -> {
                        start.await();
                        return chatMessageUseCase.send(chatRoomId, HOST_ID, "안녕하세요", "client-1");
                    }))
                    .toList();
            start.countDown();
            for (Future<SentChatMessage> result : results) {
                result.get();
            }

            assertThat(chatMessageRepository.count()).isEqualTo(1);
            Long messageId = chatMessageRepository.findAll().getFirst().getId();
            for (Future<SentChatMessage> result : results) {
                assertThat(result.get().message().messageId()).isEqualTo(messageId);
            }
            assertThat(results.stream().filter(this::notified).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 이력과_방_목록에는_본인_메시지에만_전송_ID가_담긴다() {
        chatMessageUseCase.send(chatRoomId, HOST_ID, "호스트가 보냄", "host-1");
        chatMessageUseCase.send(chatRoomId, GUEST_ID, "게스트가 보냄", "guest-1");

        assertThat(chatMessageSupporter.getMessages(chatRoomId, HOST_ID, null, 30).messages())
                .extracting(ChatMessageResponse::content, ChatMessageResponse::clientMessageId)
                .containsExactly(tuple("게스트가 보냄", null), tuple("호스트가 보냄", "host-1"));
        assertThat(chatMessageSupporter.getMessages(chatRoomId, GUEST_ID, null, 30).messages())
                .extracting(ChatMessageResponse::content, ChatMessageResponse::clientMessageId)
                .containsExactly(tuple("게스트가 보냄", "guest-1"), tuple("호스트가 보냄", null));
        assertThat(myRoom(GUEST_ID).lastMessage().clientMessageId()).isEqualTo("guest-1");
        assertThat(myRoom(HOST_ID).lastMessage().clientMessageId()).isNull();
    }

    private boolean notified(Future<SentChatMessage> result) {
        try {
            return !result.get().resent();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ChatRoomResponse myRoom(Long userId) {
        return chatRoomSupporter.getMyRooms(userId).stream()
                .filter(room -> room.chatRoomId().equals(chatRoomId))
                .findFirst()
                .orElseThrow();
    }
}
