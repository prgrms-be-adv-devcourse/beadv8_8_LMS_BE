package com.hapbang.auxiliary.search.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.hapbang.auxiliary.search.app.InfluencerReindexer;
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

        // 기동 이벤트가 넘긴 호출이 테스트 도중에 더 들어올 수 있어 횟수는 최소로 본다
        verify(reindexer, timeout(5_000).atLeast(2)).run();
        assertThat(calls).hasSizeGreaterThanOrEqualTo(2).allSatisfy(thread -> {
            assertThat(thread.getName()).startsWith("search-reindex-");
            assertThat(thread).isNotSameAs(Thread.currentThread());
        });
    }
}
