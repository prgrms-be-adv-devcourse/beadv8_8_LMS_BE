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

import com.hapbang.chat.fixture.ChatUserSeeder;
import com.hapbang.chat.out.ChatRoomRepository;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 같은 두 회원의 동시 채팅 시작.
 */
@IntegrationTest
class ChatRoomConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private ChatRoomFacade chatRoomFacade;

    @Autowired
    private ChatUserService chatUserService;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

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
