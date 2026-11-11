package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.config.ChatbotInfluencerRecommendProperties;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;

/**
 * 분야 이름 표 (#719). 테스트 시계로 보관·재시도 시간을 넘긴다(시간에 기대지 않는다). 시간은 설정값에서 계산한다.
 * 테스트마다 새 객체를 만들어 앞 테스트의 표를 쓰지 않는다.
 */
class ContentCategoryNamesTest extends ChatbotIntegrationTestBase {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private ChatbotInfluencerRecommendProperties properties;

    private ContentCategoryNames names;

    @BeforeEach
    void setUp() {
        names = new ContentCategoryNames(memberSource, properties, clockBean);
    }

    @Test
    @DisplayName("보관 시간 안에는 회원 API를 다시 부르지 않고, 지나면 다시 받는다")
    void cachesUntilTtl() {
        assertThat(names.current()).containsEntry("GAME", "게임");
        names.current();
        assertThat(memberSource.categoryCalls()).isEqualTo(1);

        memberSource.putCategoryName("GAME", "게임 방송");
        clock.advance(afterTtl());

        assertThat(names.current()).containsEntry("GAME", "게임 방송");
        assertThat(memberSource.categoryCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("다시 받지 못하면 들고 있던 표를 쓰고, 보관 시간이 아니라 재시도 시간 뒤에 다시 부른다. 회복되면 새 표")
    void keepsTableWhileFailing() {
        names.current();
        memberSource.failCategoryNames(new IllegalStateException("회원 API 장애"));
        clock.advance(afterTtl());

        assertThat(names.current()).containsEntry("GAME", "게임");
        assertThat(names.current()).containsEntry("GAME", "게임");
        assertThat(memberSource.categoryCalls()).isEqualTo(2);

        memberSource.reset();
        memberSource.putCategoryName("GAME", "게임 방송");
        clock.advance(afterRetry());
        assertThat(names.current()).containsEntry("GAME", "게임 방송");
    }

    @Test
    @DisplayName("한 번도 받지 못했으면 current()는 예외, namesOf는 코드 그대로. 재시도 시간 뒤 회원 API가 살아나면 받는다")
    void neverFetched() {
        memberSource.failCategoryNames(new IllegalStateException("회원 API 장애"));

        assertThatThrownBy(names::current).isInstanceOf(ContentCategoryNames.Unavailable.class);
        assertThat(names.namesOf(List.of("GAME", "MUSIC"))).containsExactly("GAME", "MUSIC");
        assertThat(memberSource.categoryCalls()).isEqualTo(1);

        memberSource.reset();
        clock.advance(afterRetry());
        assertThat(names.current()).containsEntry("GAME", "게임");
    }

    @Test
    @DisplayName("처음 받는 중에 들어온 호출은 예외 없이 기다렸다가 같은 표를 쓴다(회원 API는 한 번만 부른다)")
    void waitsForFirstFetch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        memberSource.blockCategoryNames(entered, release);

        FutureTask<Map<String, String>> first = new FutureTask<>(names::current);
        new Thread(first).start();
        assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        AtomicReference<Object> secondResult = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                secondResult.set(names.current());
            } catch (RuntimeException e) {
                secondResult.set(e);
            }
        });
        second.start();
        // 첫 호출이 받는 동안 두 번째 호출은 표를 기다린다(예외로 끝나지 않는다)
        // 잠금(ReentrantLock)을 기다리는 스레드는 WAITING이다
        await().atMost(WAIT).until(() -> second.getState() == Thread.State.WAITING || !second.isAlive());
        assertThat(second.isAlive()).isTrue();

        release.countDown();
        second.join(WAIT.toMillis());
        assertThat(first.get(WAIT.toSeconds(), TimeUnit.SECONDS)).containsEntry("GAME", "게임");
        assertThat(secondResult.get()).isInstanceOf(Map.class);
        assertThat(memberSource.categoryCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("화면용 namesOf는 처음 받는 중이어도 기다리지 않고 코드를 그대로 보여 준다")
    void displayDoesNotWait() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        memberSource.blockCategoryNames(entered, release);
        FutureTask<Map<String, String>> first = new FutureTask<>(names::current);
        new Thread(first).start();
        assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        // 이 스레드가 기다리면 release를 내릴 수 없어 테스트가 끝나지 않는다(상한 10초 뒤 실패)
        FutureTask<List<String>> display = new FutureTask<>(() -> names.namesOf(List.of("GAME")));
        new Thread(display).start();
        try {
            assertThat(display.get(WAIT.toSeconds(), TimeUnit.SECONDS)).containsExactly("GAME");
        } finally {
            release.countDown();
        }
        assertThat(first.get(WAIT.toSeconds(), TimeUnit.SECONDS)).containsEntry("GAME", "게임");
    }

    private Duration afterTtl() {
        return properties.categoryNamesTtl().plusSeconds(1);
    }

    private Duration afterRetry() {
        return properties.categoryNamesRetry().plusSeconds(1);
    }
}
