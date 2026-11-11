package com.hapbang.auxiliary.search.influencer.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hapbang.auxiliary.search.influencer.app.InfluencerReindexer;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 처음 적재·재색인을 기동 때와 10분 확인 때 재색인 실행기로 넘기는 연결(#773, PR #845 리뷰). 모듈 테스트 설정은 기동 재색인과 스케줄링을 꺼 두므로
 * 이 클래스만 켜서 두 빈이 실행기를 주입받아 뜨는지 본다. 기동 이벤트가 언제 가짜에 닿을지 정할 수 없어 {@code onReady}를 직접 부른다. 재색인 자체는
 * 가짜라 공용 ES 컨테이너를 쓰지 않는다. 스케줄링이 켜진 컨텍스트를 다른 클래스가 이어 쓰지 않게 닫는다.
 */
@IntegrationTest
@DirtiesContext
@TestPropertySource(properties = {"hapbang.scheduling.enabled=true", "hapbang.search.setup-on-startup=true"})
class SearchReindexWiringTest {

    @MockitoBean
    private InfluencerReindexer reindexer;

    @Autowired
    private SearchIndexStartup startup;

    @Autowired
    private SearchBatch batch;

    @Test
    @DisplayName("기동(onReady)과 10분 확인(checkIndex)이 재색인 실행기를 주입받아, 재색인을 search-reindex- 스레드에서 부른다")
    void handsOffToReindexExecutor() {
        List<Thread> calls = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> calls.add(Thread.currentThread())).when(reindexer).run();

        startup.onReady();
        batch.checkIndex();

        // 기록된 호출 수(verify)로 기다리지 않는다. 가짜는 답(calls.add)을 실행하기 전에 호출을 기록하고, 컨텍스트 기동 이벤트가 stub 전에 넘긴
        // 호출도 센다. 그래서 verify가 통과해도 calls에 하나만 있을 수 있다(CI 간헐 실패, #860). calls에 두 번 들어올 때까지 기다린다
        await().atMost(Duration.ofSeconds(5)).until(() -> calls.size() >= 2);
        assertThat(calls).allSatisfy(thread -> {
            assertThat(thread.getName()).startsWith("search-reindex-");
            assertThat(thread).isNotSameAs(Thread.currentThread());
        });
    }
}
