package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.app.dto.ChatMessagePageResponse;
import com.hapbang.chat.app.dto.ChatReceiptResponse;
import com.hapbang.chat.app.dto.ChatRoomResponse;
import com.hapbang.chat.app.exception.ChatErrorCode;
import com.hapbang.chat.app.exception.ChatException;
import com.hapbang.chat.domain.ChatRoomMember;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 누적 수신 확인 순번과 클라이언트 수신 확인(설계 문서 4.4).
 */
@IntegrationTest
class ChatReceiptTest {

    @Autowired
    private ChatReceiptService chatReceiptService;

    @Autowired
    private ChatRoomUseCase chatRoomUseCase;

    @Autowired
    private ChatReadService chatReadService;

    @Autowired
    private ChatRoomSupporter chatRoomSupporter;

    @Autowired
    private ChatMessageUseCase chatMessageUseCase;

    @Autowired
    private ChatMessageSupporter chatMessageSupporter;

    @Autowired
    private ChatUserUseCase chatUserUseCase;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long chatRoomId;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserUseCase);
        chatRoomId = chatRoomUseCase.createOrGet(HOST_ID, GUEST_ID).chatRoomId();
    }

    @Test
    void 처음_참여하면_수신_확인_순번은_미확인이다() {
        assertThat(member(HOST_ID).getLastReceivedSequence()).isNull();
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isNull();
    }

    @Test
    void 수신_확인하면_순번을_올리고_반영한_값을_돌려준다() {
        sendMessages(3);

        ChatReceiptResponse response = chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 2);

        assertThat(response).isEqualTo(new ChatReceiptResponse(chatRoomId, 1, 2));
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isEqualTo(2L);
    }

    @Test
    void 같은_값이나_낮은_값은_바꾸지_않고_현재_값으로_응답한다() {
        sendMessages(3);
        chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3);

        assertThat(chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3).lastReceivedSequence()).isEqualTo(3);
        assertThat(chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 1).lastReceivedSequence()).isEqualTo(3);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isEqualTo(3L);
    }

    @Test
    void 로그인한_회원의_순번만_바뀌고_다른_참여자의_순번은_그대로다() {
        sendMessages(2);

        chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 2);
        assertThat(member(HOST_ID).getLastReceivedSequence()).isNull();

        chatReceiptService.markReceived(chatRoomId, HOST_ID, 1, 1);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isEqualTo(2L);
        assertThat(member(HOST_ID).getLastReceivedSequence()).isEqualTo(1L);
    }

    @Test
    void 참여자가_아니면_거절한다() {
        sendMessages(1);

        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, OTHER_INFLUENCER_ID, 1, 1),
                ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
    }

    @Test
    void 나간_참여자는_거절한다() {
        sendMessages(1);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 1),
                ChatErrorCode.NOT_CHAT_ROOM_MEMBER);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isNull();
    }

    @Test
    void 없는_방이나_종료된_방은_거절한다() {
        sendMessages(1);
        assertRejected(() -> chatReceiptService.markReceived(chatRoomId + 1000, GUEST_ID, 1, 1),
                ChatErrorCode.CHAT_ROOM_NOT_FOUND);

        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.leave(chatRoomId, HOST_ID, null);

        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, HOST_ID, 1, 1),
                ChatErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void 강등된_회원은_거절한다() {
        sendMessages(1);
        chatUserUseCase.update(activeUser(GUEST_ID, "게스트", Role.USER));

        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 1),
                ChatErrorCode.CHAT_NOT_ALLOWED);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isNull();
    }

    @Test
    void 현재_참여_세대가_아니면_STALE_PARTICIPATION이다() {
        sendMessages(1);

        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 2, 1),
                ChatErrorCode.STALE_PARTICIPATION);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isNull();
    }

    @Test
    void 재입장하면_미확인으로_초기화하고_옛_세대의_수신_확인은_반영하지_않는다() {
        sendMessages(2);
        chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 2);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);

        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);

        ChatRoomMember rejoined = member(GUEST_ID);
        assertThat(rejoined.getParticipationVersion()).isEqualTo(2);
        assertThat(rejoined.getLastReceivedSequence()).isNull();
        // 재입장 이후 메시지가 있어도 옛 세대 번호의 확인은 새 세대에 반영되지 않는다
        sendMessages(1);
        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, latestSequence()),
                ChatErrorCode.STALE_PARTICIPATION);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isNull();
        assertThat(chatReceiptService.markReceived(chatRoomId, GUEST_ID, 2, latestSequence()).lastReceivedSequence())
                .isEqualTo(latestSequence());
    }

    @Test
    void 방의_마지막_순번을_넘으면_보정하지_않고_거절한다() {
        sendMessages(2);

        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3),
                ChatErrorCode.RECEIVED_SEQUENCE_OUT_OF_RANGE);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isNull();
    }

    @Test
    void 메시지가_없으면_어떤_순번도_범위_밖이다() {
        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 0),
                ChatErrorCode.RECEIVED_SEQUENCE_OUT_OF_RANGE);
        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 1),
                ChatErrorCode.RECEIVED_SEQUENCE_OUT_OF_RANGE);
    }

    @Test
    void 조회_시작점보다_작은_순번은_거절한다() {
        sendMessages(2);
        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);
        sendMessages(1);
        long visibleFrom = member(GUEST_ID).getVisibleFromSequence();

        assertRejected(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 2, visibleFrom - 1),
                ChatErrorCode.RECEIVED_SEQUENCE_OUT_OF_RANGE);
        assertThat(chatReceiptService.markReceived(chatRoomId, GUEST_ID, 2, visibleFrom).lastReceivedSequence())
                .isEqualTo(visibleFrom);
    }

    @Test
    void 수신_확인은_읽음_위치와_안_읽은_수를_바꾸지_않는다() {
        sendMessagesFrom(HOST_ID, 3);

        chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 3);

        assertThat(member(GUEST_ID).getLastReadSequence()).isZero();
        assertThat(myRoom(GUEST_ID).unreadCount()).isEqualTo(3);
    }

    @Test
    void 읽음_처리는_수신_확인_순번을_바꾸지_않는다() {
        sendMessagesFrom(HOST_ID, 3);

        chatReadService.markRead(chatRoomId, GUEST_ID, 3, null);
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isNull();

        // 읽음과 수신은 독립이라 읽음이 수신보다 앞설 수 있다
        chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 1);
        ChatRoomMember guest = member(GUEST_ID);
        assertThat(guest.getLastReceivedSequence()).isEqualTo(1L);
        assertThat(guest.getLastReadSequence()).isEqualTo(3);
    }

    @Test
    void 동시에_여러_순번을_확인해도_가장_큰_값만_남고_줄어들지_않는다() throws Exception {
        int count = 20;
        sendMessages(count);
        List<Long> sequences = new ArrayList<>(LongStream.rangeClosed(1, count).boxed().toList());
        Collections.shuffle(sequences);

        List<ChatReceiptResponse> responses = runConcurrently(count,
                index -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, sequences.get(index)));

        for (int i = 0; i < count; i++) {
            assertThat(responses.get(i).lastReceivedSequence()).isGreaterThanOrEqualTo(sequences.get(i));
        }
        assertThat(member(GUEST_ID).getLastReceivedSequence()).isEqualTo((long) count);
    }

    @Test
    void 방_순번_잠금을_다른_트랜잭션이_쥐고_있어도_수신_확인은_기다리지_않는다() throws Exception {
        sendMessages(1);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                chatRoomRepository.findByIdForUpdate(chatRoomId).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            ExecutorService ackExecutor = Executors.newSingleThreadExecutor();
            try {
                ChatReceiptResponse response = ackExecutor
                        .submit(() -> chatReceiptService.markReceived(chatRoomId, GUEST_ID, 1, 1))
                        .get(5, TimeUnit.SECONDS);
                assertThat(response.lastReceivedSequence()).isEqualTo(1);
            } finally {
                ackExecutor.shutdownNow();
            }
            assertThat(lockHolder.isDone()).as("수신 확인 동안 방 잠금이 유지됐다").isFalse();

            release.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 메시지_이력에_현재_참여_세대와_조회_시작점을_담는다() {
        sendMessages(2);
        ChatMessagePageResponse first = chatMessageSupporter.getMessages(chatRoomId, GUEST_ID, null, 30);
        assertThat(first.participationVersion()).isEqualTo(1);
        assertThat(first.visibleFromSequence()).isEqualTo(1);

        chatRoomUseCase.leave(chatRoomId, GUEST_ID, null);
        chatRoomUseCase.createOrGet(GUEST_ID, HOST_ID);

        ChatMessagePageResponse rejoined = chatMessageSupporter.getMessages(chatRoomId, GUEST_ID, null, 30);
        assertThat(rejoined.participationVersion()).isEqualTo(2);
        assertThat(rejoined.visibleFromSequence()).isEqualTo(member(GUEST_ID).getVisibleFromSequence());
        assertThat(rejoined.visibleFromSequence()).isGreaterThan(2);
    }

    private void sendMessages(int count) {
        sendMessagesFrom(HOST_ID, count);
    }

    private void sendMessagesFrom(Long senderId, int count) {
        for (int i = 0; i < count; i++) {
            chatMessageUseCase.send(chatRoomId, senderId, "메시지 " + i, null);
        }
    }

    private long latestSequence() {
        return chatRoomRepository.findById(chatRoomId).orElseThrow().getLastSequence();
    }

    private ChatRoomMember member(Long userId) {
        return chatRoomMemberRepository.findByChatRoom_IdAndUserIdAndDeletedAtIsNull(chatRoomId, userId)
                .orElseThrow();
    }

    private ChatRoomResponse myRoom(Long userId) {
        return chatRoomSupporter.getMyRooms(userId).stream()
                .filter(room -> room.chatRoomId().equals(chatRoomId))
                .findFirst()
                .orElseThrow();
    }

    private static void assertRejected(ThrowingCallable call,
            ChatErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(ChatException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private <T> List<T> runConcurrently(int count, IndexedTask<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<T> callable = () -> {
                    ready.countDown();
                    start.await();
                    return task.run(index);
                };
                futures.add(executor.submit(callable));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedTask<T> {
        T run(int index) throws Exception;
    }
}
