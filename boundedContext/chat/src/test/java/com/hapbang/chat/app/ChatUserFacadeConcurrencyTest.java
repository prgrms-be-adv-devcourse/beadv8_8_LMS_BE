package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 같은 회원의 가입·변경 이벤트가 동시에 처리될 때, 생성 충돌(PK)을 다시 시도해 변경 정보가 남는지 확인한다.
 * 실행 순서는 매번 달라지므로 여러 회원으로 반복한다. 어떤 순서든 변경 정보가 최종 결과여야 한다.
 */
@IntegrationTest
class ChatUserFacadeConcurrencyTest {

    private static final int REPEAT = 10;

    @Autowired
    private ChatUserFacade chatUserFacade;

    @Autowired
    private ChatUserRepository chatUserRepository;

    @Test
    void 가입과_변경이_동시에_처리되어도_오류_없이_변경_정보가_남는다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (long userId = 100; userId < 100 + REPEAT; userId++) {
                long id = userId;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                futures.add(executor.submit(() -> {
                    start.await();
                    chatUserFacade.syncJoined(activeUser(id, "가입닉네임", Role.USER));
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    start.await();
                    chatUserFacade.syncModified(activeUser(id, "바뀐닉네임", Role.INFLUENCER));
                    return null;
                }));
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }

                ChatUser chatUser = chatUserRepository.findById(id).orElseThrow();
                assertThat(chatUser.getNickname()).as("userId=%d", id).isEqualTo("바뀐닉네임");
                assertThat(chatUser.getRole()).as("userId=%d", id).isEqualTo(Role.INFLUENCER);
            }
            assertThat(chatUserRepository.count()).isEqualTo(REPEAT);
        } finally {
            executor.shutdownNow();
        }
    }
}
