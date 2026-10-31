package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.chat.app.dto.ChatRoomMemberResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.dto.SentChatMessage;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 커밋 뒤 따로 조립하는 방 응답(채팅 시작 응답·내 채팅방 목록)이 한 시점의 스냅샷으로 읽히는지 검증한다(invariants.md 참여 세대·가시 범위).
 * <p>
 * 응답은 참여자를 읽은 뒤 마지막 메시지·안 읽은 수를 따로 읽는다. 그 사이(마지막 메시지 조회 직전)에 다른 스레드가 게스트의
 * 퇴장 → 재입장(세대 2) → 새 메시지 전송을 커밋하게 끼워 넣는다. READ COMMITTED였다면 참여자는 세대 1인데 마지막 메시지는
 * 세대 2의 메시지가 섞인다. 응답의 모든 값이 끼어든 커밋 이전 상태여야 한다.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.hapbang.chat.app.ChatRoomResponseSnapshotTest$InterleavingInspector")
class ChatRoomResponseSnapshotTest {

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    private Long chatRoomId;
    private Long beforeMessageId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
        beforeMessageId = chatMessageUseCase.send(chatRoomId, HOST_ID, "끼어들기 전", null).message().messageId();
    }

    @AfterEach
    void tearDown() {
        InterleavingInspector.disarm();
    }

    @Test
    void 채팅_시작_응답은_조회_도중_퇴장_재입장_전송이_커밋돼도_한_시점의_값으로_만든다() {
        AtomicReference<Long> afterMessageId = armRejoinAndSend(ChatRoomSupporter.class.getName() + ".toResponse");

        ChatRoomResponse response = chatRoomSupporter.toResponse(chatRoomId, GUEST_ID);

        assertThat(afterMessageId.get()).as("끼어든 전송이 실제로 커밋됐다").isNotNull();
        assertConsistentBeforeInterleaving(response, afterMessageId.get());
        // 끼어든 커밋 뒤 새로 읽으면 세대 2와 그 세대의 메시지가 함께 보인다(대조군)
        ChatRoomResponse fresh = chatRoomSupporter.toResponse(chatRoomId, GUEST_ID);
        assertThat(guestOf(fresh).participationVersion()).isEqualTo(2);
        assertThat(fresh.lastMessage().messageId()).isEqualTo(afterMessageId.get());
    }

    @Test
    void 내_채팅방_목록도_조회_도중_퇴장_재입장_전송이_커밋돼도_한_시점의_값으로_만든다() {
        AtomicReference<Long> afterMessageId = armRejoinAndSend(ChatRoomSupporter.class.getName() + ".getMyRooms");

        ChatRoomResponse response = chatRoomSupporter.getMyRooms(GUEST_ID).getFirst();

        assertThat(afterMessageId.get()).as("끼어든 전송이 실제로 커밋됐다").isNotNull();
        assertConsistentBeforeInterleaving(response, afterMessageId.get());
    }

    /** 응답이 끼어든 커밋 이전 상태로만 이루어졌는지: 게스트 세대 1, 마지막 메시지는 세대 1에서 본 메시지, 안 읽은 수 1 */
    private void assertConsistentBeforeInterleaving(ChatRoomResponse response, Long afterMessageId) {
        assertThat(guestOf(response).participationVersion()).isEqualTo(1);
        assertThat(guestOf(response).active()).isTrue();
        assertThat(response.lastMessage()).isNotNull();
        assertThat(response.lastMessage().messageId()).isEqualTo(beforeMessageId).isNotEqualTo(afterMessageId);
        assertThat(response.unreadCount()).isEqualTo(1);
    }

    /**
     * 지정한 트랜잭션에서 메시지 테이블을 처음 읽기 직전에, 다른 스레드로 게스트 퇴장 → 재입장 → 호스트 전송을 커밋하고 기다린다.
     *
     * @return 끼어든 전송의 메시지 ID(커밋 뒤 채워진다)
     */
    private AtomicReference<Long> armRejoinAndSend(String transactionName) {
        AtomicReference<Long> afterMessageId = new AtomicReference<>();
        InterleavingInspector.arm(transactionName, () -> {
            chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
            chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
            SentChatMessage after = chatMessageUseCase.send(chatRoomId, HOST_ID, "끼어든 뒤", null);
            afterMessageId.set(after.message().messageId());
        });
        return afterMessageId;
    }

    private static ChatRoomMemberResponse guestOf(ChatRoomResponse response) {
        return response.members().stream().filter(member -> member.userId().equals(GUEST_ID)).findFirst()
                .orElseThrow();
    }

    /**
     * 지정한 트랜잭션이 메시지 테이블을 처음 읽기 직전에 한 번, 다른 스레드에서 동작을 실행해 커밋을 끼워 넣는다.
     */
    public static class InterleavingInspector implements StatementInspector {

        private static final AtomicReference<String> TRANSACTION = new AtomicReference<>();
        private static final AtomicReference<Runnable> ACTION = new AtomicReference<>();

        static void arm(String transactionName, Runnable action) {
            TRANSACTION.set(transactionName);
            ACTION.set(action);
        }

        static void disarm() {
            TRANSACTION.set(null);
            ACTION.set(null);
        }

        @Override
        public String inspect(String sql) {
            String target = TRANSACTION.get();
            if (target != null && target.equals(TransactionSynchronizationManager.getCurrentTransactionName())
                    && sql.toLowerCase(Locale.ROOT).contains("from chats.chat_message ")) {
                Runnable action = ACTION.getAndSet(null);
                if (action != null) {
                    TRANSACTION.set(null);
                    try {
                        CompletableFuture.runAsync(action).get(30, TimeUnit.SECONDS);
                    } catch (Exception exception) {
                        throw new IllegalStateException("끼워 넣은 커밋이 실패했습니다.", exception);
                    }
                }
            }
            return sql;
        }
    }
}
