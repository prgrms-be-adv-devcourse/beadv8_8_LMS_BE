package com.hapbang.auxiliary.chatbot.app;

import static com.hapbang.auxiliary.chatbot.support.CopyRows.appearance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncRunStatus;
import com.hapbang.auxiliary.chatbot.domain.SyncTrigger;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.exception.ChatbotErrorCode;
import com.hapbang.auxiliary.chatbot.exception.ChatbotException;
import com.hapbang.auxiliary.chatbot.out.SyncRunRepository;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.shared.appearance.domain.AppearanceResult;

/** 싱크 겹침 방지 (#716). 실제 PostgreSQL advisory lock(앱의 JobLock 빈)으로 본다 */
class ChatbotSyncLockTest extends ChatbotIntegrationTestBase {

    @Autowired
    private ChatbotSyncUseCase chatbotSyncUseCase;

    @Autowired
    private SyncRunRepository syncRunRepository;

    @Test
    @DisplayName("챗봇 작업의 락 번호는 겹치지 않고 그 작업을 더한 이슈 번호 × 1000 + 순번이다(같은 database를 쓰는 다른 모듈과 겹치지 않게)")
    void lockKeys() {
        assertThat(Arrays.stream(ChatbotJob.values()).map(ChatbotJob::lockKey).distinct().count())
                .isEqualTo(ChatbotJob.values().length);
        assertThat(Arrays.stream(ChatbotJob.values()).map(ChatbotJob::tag).distinct().count())
                .isEqualTo(ChatbotJob.values().length);
        assertThat(ChatbotJob.values()).allSatisfy(job -> {
            // 출연 싱크 #716, FAQ 맞추기 #715
            assertThat(job.lockKey() / 1000).isIn(715L, 716L);
            assertThat(job.tag()).startsWith("chatbot-");
        });
    }

    @Test
    @DisplayName("같은 종류가 실행 중이면 배치는 SKIPPED로 남기고 건너뛰고, 관리자 실행은 409다. 다른 종류는 그대로 돈다")
    void overlap() throws Exception {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0));
        CountDownLatch gate = source.hold();
        CompletableFuture<SyncRun> running = CompletableFuture.supplyAsync(
                () -> chatbotSyncUseCase.runBatch(SyncType.APPEARANCE, SyncTrigger.SCHEDULED));
        await().atMost(Duration.ofSeconds(10)).until(() -> source.calls() == 1);

        SyncRun skipped = chatbotSyncUseCase.runBatch(SyncType.APPEARANCE, SyncTrigger.RETRY);
        assertThatThrownBy(() -> chatbotSyncUseCase.startManual(SyncType.APPEARANCE, 7L))
                .isInstanceOfSatisfying(ChatbotException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChatbotErrorCode.SYNC_ALREADY_RUNNING));
        gate.countDown();

        assertThat(skipped.getStatus()).isEqualTo(SyncRunStatus.SKIPPED);
        assertThat(skipped.getError()).contains("락");
        assertThat(running.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(chatbotSyncUseCase.runBatch(SyncType.APPEARANCE_REVIEW, SyncTrigger.SCHEDULED).getStatus())
                .isEqualTo(SyncRunStatus.SUCCEEDED);
        // 끝나면 락을 풀어 다시 실행된다
        assertThat(chatbotSyncUseCase.runBatch(SyncType.APPEARANCE, SyncTrigger.SCHEDULED).getStatus())
                .isEqualTo(SyncRunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("관리자 실행: 요청 스레드에서 락을 잡고 RUNNING 회차를 바로 주며, 비동기 실행이 끝나면 락을 푼다")
    void manual() {
        source.put(appearance(1, AppearanceResult.SCHEDULED, 0));
        CountDownLatch gate = source.hold();

        SyncRun started = chatbotSyncUseCase.startManual(SyncType.APPEARANCE, 7L);

        assertThat(started.getStatus()).isEqualTo(SyncRunStatus.RUNNING);
        assertThat(started.getTrigger()).isEqualTo(SyncTrigger.ADMIN);
        assertThat(started.getRequestedBy()).isEqualTo(7L);
        // 비동기 작업이 락을 쥐고 있다
        assertThat(chatbotSyncUseCase.runBatch(SyncType.APPEARANCE, SyncTrigger.RETRY).getStatus())
                .isEqualTo(SyncRunStatus.SKIPPED);
        gate.countDown();

        await().atMost(Duration.ofSeconds(10)).until(() ->
                syncRunRepository.findById(started.getId()).orElseThrow().getStatus() == SyncRunStatus.SUCCEEDED);
        // 회차를 적은 뒤 락을 풀므로 잠깐 409일 수 있다
        Long again = await().atMost(Duration.ofSeconds(10)).ignoreException(ChatbotException.class)
                .until(() -> chatbotSyncUseCase.startManual(SyncType.APPEARANCE, 7L).getId(), id -> id != null);
        await().atMost(Duration.ofSeconds(10)).until(() ->
                syncRunRepository.findById(again).orElseThrow().getStatus() == SyncRunStatus.SUCCEEDED);
    }
}
