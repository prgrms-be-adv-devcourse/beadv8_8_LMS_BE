package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DefaultChatConnectionRecheckServiceTest {

    private final LocalChatConnections connections = mock(LocalChatConnections.class);
    private final ChatRecipientStateReader reader = mock(ChatRecipientStateReader.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final DefaultChatConnectionRecheckService service = new DefaultChatConnectionRecheckService(connections,
            reader, scheduler, meterRegistry, 2, 3, Duration.ofSeconds(1), Duration.ofSeconds(10));

    @Test
    void 지금도_채팅할_수_없는_회원만_닫는다() {
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
        when(reader.findChatAllowedUserIds(List.of(HOST_ID))).thenReturn(Set.of());

        service.recheckUser(HOST_ID);

        verify(connections).closeNotAllowed(HOST_ID);
    }

    @Test
    void 자격이_복구된_회원은_닫지_않는다() {
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
        when(reader.findChatAllowedUserIds(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));

        service.recheckUser(HOST_ID);

        verify(connections, never()).closeNotAllowed(any());
    }

    @Test
    void 로컬_연결이_없으면_조회하지_않는다() {
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of());

        service.recheckUser(HOST_ID);

        verifyNoInteractions(reader);
    }

    @Test
    void 조회에_실패하면_닫지_않는다() {
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
        when(reader.findChatAllowedUserIds(any())).thenThrow(new QueryTimeoutException("DB 지연"));

        service.recheckUser(HOST_ID);

        verify(connections, never()).closeNotAllowed(any());
    }

    @Test
    void 전체_재검사는_배치_크기로_나눠_조회하고_한_배치의_실패가_다른_배치를_막지_않는다() {
        when(connections.connectedUserIds()).thenReturn(new LinkedHashSet<>(List.of(HOST_ID, GUEST_ID, ADMIN_ID)));
        when(reader.findChatAllowedUserIds(List.of(HOST_ID, GUEST_ID)))
                .thenThrow(new QueryTimeoutException("DB 지연"));
        when(reader.findChatAllowedUserIds(List.of(ADMIN_ID))).thenReturn(Set.of());

        service.recheckAllConnected();

        verify(reader, times(2)).findChatAllowedUserIds(any());
        verify(connections).closeNotAllowed(ADMIN_ID);
        verify(connections, never()).closeNotAllowed(HOST_ID);
        verify(connections, never()).closeNotAllowed(GUEST_ID);
    }

    @Test
    void 조회에_실패한_회원만_backoff로_다시_확인하고_그때_연결이_남은_회원만_조회한다() {
        when(connections.connectedUserIds()).thenReturn(new LinkedHashSet<>(List.of(HOST_ID, GUEST_ID, ADMIN_ID)));
        when(reader.findChatAllowedUserIds(List.of(HOST_ID, GUEST_ID)))
                .thenThrow(new QueryTimeoutException("DB 지연"));
        when(reader.findChatAllowedUserIds(List.of(ADMIN_ID))).thenReturn(Set.of(ADMIN_ID));
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        Instant before = Instant.now();

        service.recheckAllConnected();

        // 실패한 묶음 하나만 예약한다. 첫 재시도는 initial-delay 뒤다
        verify(scheduler, times(1)).schedule(retry.capture(), at.capture());
        assertThat(at.getValue()).isBetween(before.plusMillis(900), Instant.now().plusSeconds(1));
        assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.LOOKUP_FAILURE_METRIC).count())
                .isEqualTo(1);

        // 재시도 전에 HOST의 연결이 끊겼다
        when(connections.connectedAmong(List.of(HOST_ID, GUEST_ID))).thenReturn(Set.of(GUEST_ID));
        when(reader.findChatAllowedUserIds(List.of(GUEST_ID))).thenReturn(Set.of());
        retry.getValue().run();

        verify(reader).findChatAllowedUserIds(List.of(GUEST_ID));
        verify(connections).closeNotAllowed(GUEST_ID);
        verify(connections, never()).closeNotAllowed(HOST_ID);
    }

    @Test
    void 조회가_실패했다가_회복되면_주기_재검사를_기다리지_않고_지금도_채팅할_수_없는_회원을_닫는다() {
        ThreadPoolTaskScheduler realScheduler = startedScheduler();
        try {
            DefaultChatConnectionRecheckService retrying = new DefaultChatConnectionRecheckService(connections,
                    reader, realScheduler, meterRegistry, 500, 3, Duration.ofMillis(50), Duration.ofMillis(200));
            when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
            when(reader.findChatAllowedUserIds(List.of(HOST_ID)))
                    .thenThrow(new QueryTimeoutException("DB 지연"))
                    .thenReturn(Set.of());

            retrying.recheckUser(HOST_ID);

            verify(connections, never()).closeNotAllowed(any());
            verify(connections, timeout(2_000)).closeNotAllowed(HOST_ID);
            assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.LOOKUP_FAILURE_METRIC).count())
                    .isEqualTo(1);
            assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.RETRY_EXHAUSTED_METRIC).count())
                    .isZero();
        } finally {
            realScheduler.shutdown();
        }
    }

    @Test
    void 재시도_상한에_닿으면_지표를_세고_더_조회하지_않는다() {
        ThreadPoolTaskScheduler realScheduler = startedScheduler();
        try {
            DefaultChatConnectionRecheckService retrying = new DefaultChatConnectionRecheckService(connections,
                    reader, realScheduler, meterRegistry, 500, 2, Duration.ofMillis(20), Duration.ofMillis(40));
            when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
            when(reader.findChatAllowedUserIds(any())).thenThrow(new QueryTimeoutException("DB 장애"));

            retrying.recheckUser(HOST_ID);

            await().atMost(Duration.ofSeconds(2)).until(() -> meterRegistry
                    .counter(DefaultChatConnectionRecheckService.RETRY_EXHAUSTED_METRIC).count() == 1);
            // 첫 확인 1번 + 재시도 2번에서 멈춘다(빠른 반복 없음)
            verify(reader, after(300).times(3)).findChatAllowedUserIds(any());
            assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.LOOKUP_FAILURE_METRIC).count())
                    .isEqualTo(3);
            verify(connections, never()).closeNotAllowed(any());
        } finally {
            realScheduler.shutdown();
        }
    }

    @Test
    void 재시도는_initial_delay부터_두_배씩_늦추고_max_delay에서_멈추며_상한_뒤에는_더_예약하지_않는다() {
        // 설정값(1s·10s)에 재시도 5번을 주어 상한(10s)에 닿는 것까지 본다. 운영 설정(3번)은 1s·2s·4s 뒤 주기 재검사에 맡긴다
        DefaultChatConnectionRecheckService retrying = new DefaultChatConnectionRecheckService(connections, reader,
                scheduler, meterRegistry, 500, 5, Duration.ofSeconds(1), Duration.ofSeconds(10));
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
        when(reader.findChatAllowedUserIds(any())).thenThrow(new QueryTimeoutException("DB 장애"));
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        List<Long> delaySeconds = new ArrayList<>();

        Instant triggered = Instant.now();
        retrying.recheckUser(HOST_ID);
        for (int scheduled = 1; scheduled <= 5; scheduled++) {
            verify(scheduler, times(scheduled)).schedule(task.capture(), at.capture());
            // 반올림: 예약 시각은 호출 시각 + 지연이다
            delaySeconds.add(Math.round(Duration.between(triggered, at.getValue()).toMillis() / 1000.0));
            triggered = Instant.now();
            task.getValue().run();
        }

        assertThat(delaySeconds).containsExactly(1L, 2L, 4L, 8L, 10L);
        // 첫 확인 + 재시도 5번 뒤에는 더 예약하지 않는다
        verify(scheduler, times(5)).schedule(any(Runnable.class), any(Instant.class));
        verify(reader, times(6)).findChatAllowedUserIds(List.of(HOST_ID));
        assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.RETRY_EXHAUSTED_METRIC).count())
                .isEqualTo(1);
        verify(connections, never()).closeNotAllowed(any());
    }

    @Test
    void 같은_회원의_재검사가_몰려도_재시도_예약은_회원마다_하나다() {
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
        when(reader.findChatAllowedUserIds(any())).thenThrow(new QueryTimeoutException("DB 지연"));

        for (int i = 0; i < 5_000; i++) {
            service.recheckUser(HOST_ID);
        }

        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        assertThat(service.pendingRetryUsers()).isEqualTo(1);
        assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.RETRY_COALESCED_METRIC).count())
                .isEqualTo(4_999);
    }

    @Test
    void 재시도를_기다리는_회원이_상한이면_예약하지_않고_세며_주기_재검사가_그_회원을_다시_본다() {
        service.setMaxPendingRetryUsers(2);
        when(connections.connectedAmong(any())).thenAnswer(invocation -> Set.copyOf(invocation.getArgument(0)));
        when(reader.findChatAllowedUserIds(any())).thenThrow(new QueryTimeoutException("DB 지연"));

        service.recheckUser(HOST_ID);
        service.recheckUser(GUEST_ID);
        service.recheckUser(ADMIN_ID);

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        assertThat(service.pendingRetryUsers()).isEqualTo(2);
        assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.RETRY_DROPPED_METRIC).count())
                .isEqualTo(1);

        // 조회가 회복되면 주기 재검사가 예약에서 빠진 회원도 다시 본다
        reset(reader);
        when(connections.connectedUserIds()).thenReturn(new LinkedHashSet<>(List.of(HOST_ID, GUEST_ID, ADMIN_ID)));
        when(reader.findChatAllowedUserIds(List.of(HOST_ID, GUEST_ID))).thenReturn(Set.of(HOST_ID, GUEST_ID));
        when(reader.findChatAllowedUserIds(List.of(ADMIN_ID))).thenReturn(Set.of());

        service.recheckAllConnected();

        verify(connections).closeNotAllowed(ADMIN_ID);
    }

    @Test
    void 예약한_재시도가_실행되면_기다리는_회원에서_빠진다() {
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
        when(reader.findChatAllowedUserIds(List.of(HOST_ID))).thenThrow(new QueryTimeoutException("DB 지연"))
                .thenReturn(Set.of(HOST_ID));
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        service.recheckUser(HOST_ID);
        verify(scheduler).schedule(retry.capture(), any(Instant.class));
        assertThat(service.pendingRetryUsers()).isEqualTo(1);

        retry.getValue().run();

        assertThat(service.pendingRetryUsers()).isZero();
        verify(connections, never()).closeNotAllowed(any());
    }

    private static ThreadPoolTaskScheduler startedScheduler() {
        ThreadPoolTaskScheduler realScheduler = new ThreadPoolTaskScheduler();
        realScheduler.setPoolSize(1);
        realScheduler.initialize();
        return realScheduler;
    }
}
