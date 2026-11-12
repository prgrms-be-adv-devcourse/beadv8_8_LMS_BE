package com.hapbang.auxiliary.search.common.in;

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

import com.hapbang.auxiliary.search.common.app.SynonymsCheck;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 동의어 묶음 확인을 재색인 실행기로 넘기는 연결(#895 리뷰). 모듈 테스트 설정은 스케줄링을 꺼 두므로 이 클래스만 켜서 배치가 실행기를 주입받아 뜨는지 본다.
 * 확인 자체는 가짜라 공용 ES 컨테이너를 쓰지 않는다. 스케줄링이 켜진 컨텍스트를 다른 클래스가 이어 쓰지 않게 닫는다.
 */
@IntegrationTest
@DirtiesContext
@TestPropertySource(properties = "hapbang.scheduling.enabled=true")
class SynonymsCheckWiringTest {

    @MockitoBean
    private SynonymsCheck synonymsCheck;

    @Autowired
    private SynonymsCheckBatch batch;

    @Test
    @DisplayName("10분 확인(checkSynonyms)이 재색인 실행기를 주입받아, 동의어 확인을 search-reindex- 스레드에서 부른다. 앱 공용 스케줄러 스레드를 붙잡지 않는다")
    void handsOffToReindexExecutor() {
        List<Thread> calls = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> calls.add(Thread.currentThread())).when(synonymsCheck).run();

        batch.checkSynonyms();

        await().atMost(Duration.ofSeconds(5)).until(() -> !calls.isEmpty());
        assertThat(calls).allSatisfy(thread -> {
            assertThat(thread.getName()).startsWith("search-reindex-");
            assertThat(thread).isNotSameAs(Thread.currentThread());
        });
    }
}
