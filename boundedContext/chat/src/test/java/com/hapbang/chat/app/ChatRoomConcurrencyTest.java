package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.domain.ChatRoom;
import com.hapbang.chat.domain.ChatRoomEventType;
import com.hapbang.chat.domain.ChatRoomLog;
import com.hapbang.chat.domain.ChatRoomStatus;
import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomLogRepository;
import com.hapbang.chat.out.ChatRoomMemberRepository;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 수용 테스트 6: 동시 생성, 동시 마지막 퇴장, 생성/마지막 퇴장 경합.
 */
@IntegrationTest
class ChatRoomConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private ChatRoomFacade chatRoomFacade;

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatUserService chatUserService;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private ChatRoomLogRepository chatRoomLogRepository;

    @BeforeEach
    void setUp() {
        ChatUserSeeder.seedDefaultUsers(chatUserService);
    }

    @Test
    void 같은_두_사람이_동시에_채팅을_시작해도_활성_방은_하나다() throws Exception {
        List<Long> roomIds = runConcurrently(THREADS, index -> index % 2 == 0
                ? chatRoomFacade.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId()
                : chatRoomFacade.createOrGet(GUEST_ID, HOST_ID).room().chatRoomId());

        assertThat(roomIds).hasSize(THREADS).containsOnly(roomIds.getFirst());
        assertThat(chatRoomRepository.count()).isEqualTo(1);
    }

    @Test
    void 두_사람이_동시에_나가면_방은_한_번만_종료된다() throws Exception {
        Long chatRoomId = chatRoomFacade.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();

        List<Boolean> ended = runConcurrently(2, index -> chatRoomService
                .leave(chatRoomId, index == 0 ? HOST_ID : GUEST_ID, null).roomEnded());

        assertThat(ended).containsExactlyInAnyOrder(true, false);
        assertThat(chatRoomRepository.findById(chatRoomId).orElseThrow().getStatus())
                .isEqualTo(ChatRoomStatus.ENDED);
        assertThat(chatRoomLogRepository.findByChatRoom_IdOrderByIdAsc(chatRoomId))
                .extracting(ChatRoomLog::getEventType)
                .filteredOn(type -> type == ChatRoomEventType.ROOM_ENDED)
                .hasSize(1);
    }

    @Test
    void 마지막_퇴장과_채팅_시작이_겹쳐도_종료된_방을_되살리지_않고_활성_방은_하나다() throws Exception {
        Long chatRoomId = chatRoomFacade.createOrGet(HOST_ID, GUEST_ID).room().chatRoomId();
        chatRoomService.leave(chatRoomId, GUEST_ID, null);

        List<Long> results = runConcurrently(2, index -> index == 0
                ? chatRoomService.leave(chatRoomId, HOST_ID, null).roomEnded() ? -1L : 0L
                : chatRoomFacade.createOrGet(GUEST_ID, HOST_ID).room().chatRoomId());
        Long startedRoomId = results.stream().filter(id -> id > 0).findFirst().orElseThrow();

        List<ChatRoom> activeRooms = chatRoomRepository.findAll().stream()
                .filter(room -> room.getStatus() == ChatRoomStatus.ACTIVE)
                .toList();
        assertThat(activeRooms).extracting(ChatRoom::getId).containsExactly(startedRoomId);
        ChatRoom original = chatRoomRepository.findById(chatRoomId).orElseThrow();
        if (original.getStatus() == ChatRoomStatus.ENDED) {
            assertThat(startedRoomId).isNotEqualTo(chatRoomId);
        } else {
            assertThat(startedRoomId).isEqualTo(chatRoomId);
        }
        assertThat(chatRoomMemberRepository.findByChatRoom_IdAndDeletedAtIsNull(startedRoomId))
                .hasSize(2)
                .allMatch(member -> member.isActive() || startedRoomId.equals(chatRoomId));
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
            ready.await(5, TimeUnit.SECONDS);
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
