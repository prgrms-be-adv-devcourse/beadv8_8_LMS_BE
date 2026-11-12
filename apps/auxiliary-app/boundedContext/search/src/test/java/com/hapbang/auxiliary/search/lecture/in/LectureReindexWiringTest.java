package com.hapbang.auxiliary.search.lecture.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hapbang.auxiliary.search.lecture.app.LectureReindexer;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 강의 처음 적재·재색인을 기동 때와 10분 확인 때 재색인 실행기로 넘기는 연결(#884). 인플루언서 {@code SearchReindexWiringTest}와 같은 방식이다.
 * 재색인 자체는 가짜라 공용 ES 컨테이너를 쓰지 않는다. 스케줄링이 켜진 컨텍스트를 다른 클래스가 이어 쓰지 않게 닫는다.
 */
@IntegrationTest
@DirtiesContext
@TestPropertySource(properties = {"hapbang.scheduling.enabled=true", "hapbang.search.setup-on-startup=true"})
class LectureReindexWiringTest {

    @MockitoBean
    private LectureReindexer reindexer;

    @Autowired
    private LectureIndexStartup startup;

    @Autowired
    private LectureSearchBatch batch;

    @Test
    @DisplayName("기동(onReady)과 10분 확인(checkIndex)이 재색인 실행기를 주입받아, 강의 재색인을 search-reindex- 스레드에서 부른다")
    void handsOffToReindexExecutor() {
        List<Thread> calls = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> calls.add(Thread.currentThread())).when(reindexer).run();

        startup.onReady();
        batch.checkIndex();

        // 기동 이벤트가 stub 전에 넘긴 호출도 있어 기록된 호출 수가 아니라 calls에 두 번 들어올 때까지 기다린다(SearchReindexWiringTest, #860)
        await().atMost(Duration.ofSeconds(5)).until(() -> calls.size() >= 2);
        assertThat(calls).allSatisfy(thread -> {
            assertThat(thread.getName()).startsWith("search-reindex-");
            assertThat(thread).isNotSameAs(Thread.currentThread());
        });
    }
}
