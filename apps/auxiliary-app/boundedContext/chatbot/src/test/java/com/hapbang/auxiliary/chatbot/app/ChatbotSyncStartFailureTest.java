package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.task.TaskExecutor;

import com.hapbang.auxiliary.chatbot.config.ChatbotSyncProperties;
import com.hapbang.auxiliary.chatbot.domain.SyncRun;
import com.hapbang.auxiliary.chatbot.domain.SyncType;
import com.hapbang.auxiliary.chatbot.out.MaskedMemberStore;
import com.hapbang.auxiliary.chatbot.out.SyncMismatchRepository;
import com.hapbang.auxiliary.chatbot.out.SyncRunRepository;
import com.hapbang.auxiliary.global.scheduling.JobLock;

/** 관리자 싱크 시작 실패 (#835): 실패 기록까지 실패해도 시작을 막은 원래 예외가 나간다 */
class ChatbotSyncStartFailureTest {

    private final JobLock jobLock = mock(JobLock.class);
    private final JobLock.Lease lease = mock(JobLock.Lease.class);
    private final SyncRunRepository syncRunRepository = mock(SyncRunRepository.class);
    private final TaskExecutor taskExecutor = mock(TaskExecutor.class);
    private final ChatbotSyncUseCase useCase = new ChatbotSyncUseCase(targets(), jobLock, syncRunRepository,
            mock(SyncMismatchRepository.class), mock(MaskedMemberStore.class), properties(), mock(SyncMetrics.class),
            taskExecutor, Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("실행을 넘기지 못하고 실패 기록도 실패하면: 원래 예외가 나가고 기록 실패는 suppressed로 붙는다. 락은 푼다")
    void originalExceptionWinsOverRecordFailure() {
        doReturn(Optional.of(lease)).when(jobLock).tryAcquire(any());
        AtomicInteger saves = new AtomicInteger();
        IllegalStateException recordFailure = new IllegalStateException("DB 장애");
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 1) {
                return invocation.getArgument(0);
            }
            throw recordFailure;
        }).when(syncRunRepository).save(any(SyncRun.class));
        RejectedExecutionException rejected = new RejectedExecutionException("큐가 가득 참");
        doThrow(rejected).when(taskExecutor).execute(any());

        Throwable thrown = catchThrowable(() -> useCase.startManual(SyncType.APPEARANCE, 7L));

        assertThat(thrown).isSameAs(rejected);
        assertThat(thrown.getSuppressed()).containsExactly(recordFailure);
        assertThat(saves).hasValue(2);
        verify(lease).close();
    }

    private static List<SyncTarget<?>> targets() {
        List<SyncTarget<?>> targets = new ArrayList<>();
        for (SyncType type : SyncType.values()) {
            SyncTarget<?> target = mock(SyncTarget.class);
            doReturn(type).when(target).type();
            doReturn(ChatbotJob.APPEARANCE_SYNC).when(target).job();
            targets.add(target);
        }
        return targets;
    }

    // 운영과 같은 바인딩(@DefaultValue)으로 기본값을 쓴다
    private static ChatbotSyncProperties properties() {
        return new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("hapbang.chatbot.sync", ChatbotSyncProperties.class);
    }
}
