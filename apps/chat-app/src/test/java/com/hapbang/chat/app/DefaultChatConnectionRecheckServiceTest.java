package com.hapbang.chat.app;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.hapbang.chat.app.port.LocalChatConnections;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 재검사 서비스를 운영 생성자로 만들고(재시도는 서비스 전용 스케줄러가 실제로 실행한다) 연결 닫기·자격 조회 호출과 운영 지표로 확인한다.
 */
class DefaultChatConnectionRecheckServiceTest {

    private final LocalChatConnections connections = mock(LocalChatConnections.class);
    private final ChatRecipientStateReader reader = mock(ChatRecipientStateReader.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<DefaultChatConnectionRecheckService> created = new ArrayList<>();
    /** 운영 기본 간격(1s~10s). 재시도가 실행되기 전에 끝나는 테스트가 쓴다 */
    private final DefaultChatConnectionRecheckService service = service(2, 3, Duration.ofSeconds(1),
            Duration.ofSeconds(10));

    @AfterEach
    void tearDown() {
        created.forEach(DefaultChatConnectionRecheckService::destroy);
    }

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
        DefaultChatConnectionRecheckService retrying = service(2, 3, Duration.ofMillis(300), Duration.ofSeconds(1));
        when(connections.connectedUserIds()).thenReturn(new LinkedHashSet<>(List.of(HOST_ID, GUEST_ID, ADMIN_ID)));
        when(reader.findChatAllowedUserIds(List.of(HOST_ID, GUEST_ID)))
                .thenThrow(new QueryTimeoutException("DB 지연"));
        when(reader.findChatAllowedUserIds(List.of(ADMIN_ID))).thenReturn(Set.of(ADMIN_ID));
        // 재시도 전에 HOST의 연결이 끊겼다
        when(connections.connectedAmong(List.of(HOST_ID, GUEST_ID))).thenReturn(Set.of(GUEST_ID));
        when(reader.findChatAllowedUserIds(List.of(GUEST_ID))).thenReturn(Set.of());
        List<Long> closedAt = new CopyOnWriteArrayList<>();
        when(connections.closeNotAllowed(GUEST_ID)).thenAnswer(invocation -> {
            closedAt.add(System.nanoTime());
            return 1;
        });
        long before = System.nanoTime();

        retrying.recheckAllConnected();

        assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.LOOKUP_FAILURE_METRIC).count())
                .isEqualTo(1);
        verify(connections, timeout(3_000)).closeNotAllowed(GUEST_ID);
        // 첫 재시도는 initial-delay 뒤다(스케줄러 시각 오차 몇 ms는 허용)
        assertThat(Duration.ofNanos(closedAt.getFirst() - before)).isGreaterThan(Duration.ofMillis(250));
        // 실패한 묶음 하나만 다시 확인하고, 그때 연결이 남은 회원만 조회한다
        verify(reader, times(1)).findChatAllowedUserIds(List.of(HOST_ID, GUEST_ID));
        verify(reader).findChatAllowedUserIds(List.of(GUEST_ID));
        verify(connections, never()).closeNotAllowed(HOST_ID);
        verify(connections, never()).closeNotAllowed(ADMIN_ID);
    }

    @Test
    void 조회가_실패했다가_회복되면_주기_재검사를_기다리지_않고_지금도_채팅할_수_없는_회원을_닫는다() {
        DefaultChatConnectionRecheckService retrying = service(500, 3, Duration.ofMillis(50),
                Duration.ofMillis(200));
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
    }

    @Test
    void 재시도_상한에_닿으면_지표를_세고_더_조회하지_않는다() {
        DefaultChatConnectionRecheckService retrying = service(500, 2, Duration.ofMillis(20), Duration.ofMillis(40));
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
    }

    @Test
    void 재시도는_initial_delay부터_두_배씩_늦추고_max_delay에서_멈추며_상한_뒤에는_더_예약하지_않는다() {
        // 100ms·500ms에 재시도 5번을 주어 상한(500ms)에 닿는 것까지 본다. 운영 설정(1s·10s, 3번)은 1s·2s·4s 뒤 주기 재검사에 맡긴다
        DefaultChatConnectionRecheckService retrying = service(500, 5, Duration.ofMillis(100), Duration.ofMillis(500));
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
        List<Long> lookedUpAt = new CopyOnWriteArrayList<>();
        when(reader.findChatAllowedUserIds(any())).thenAnswer(invocation -> {
            lookedUpAt.add(System.nanoTime());
            throw new QueryTimeoutException("DB 장애");
        });

        retrying.recheckUser(HOST_ID);

        await().atMost(Duration.ofSeconds(10)).until(() -> meterRegistry
                .counter(DefaultChatConnectionRecheckService.RETRY_EXHAUSTED_METRIC).count() == 1);
        // 첫 확인 + 재시도 5번 뒤에는 더 예약하지 않는다
        verify(reader, after(700).times(6)).findChatAllowedUserIds(List.of(HOST_ID));
        List<Long> gapsMillis = new ArrayList<>();
        for (int i = 1; i < lookedUpAt.size(); i++) {
            gapsMillis.add(Duration.ofNanos(lookedUpAt.get(i) - lookedUpAt.get(i - 1)).toMillis());
        }
        // 간격은 예약한 지연보다 짧지 않다(스케줄러 시각 오차 몇 ms는 허용): 100·200·400·500·500
        List<Long> expected = List.of(100L, 200L, 400L, 500L, 500L);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(gapsMillis.get(i)).as("재시도 %d의 간격", i + 1).isGreaterThan(expected.get(i) - 20);
        }
        // 상한에서 멈춘다: 두 배로 늘었다면 1600ms였을 마지막 간격
        assertThat(gapsMillis.getLast()).isLessThan(1_600);
        verify(connections, never()).closeNotAllowed(any());
    }

    @Test
    void 같은_회원의_재검사가_몰려도_재시도_예약은_회원마다_하나다() {
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
        when(reader.findChatAllowedUserIds(any())).thenThrow(new QueryTimeoutException("DB 지연"));

        // 예약이 이미 있는 상태에서 반복 요청해도 하나만 유지하는 정책을 검증한다.
        // 같은 실패를 수천 번 반복하는 부하 측정은 이 정책 테스트의 범위가 아니다.
        int requests = 20;
        for (int i = 0; i < requests; i++) {
            service.recheckUser(HOST_ID);
        }

        assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.RETRY_COALESCED_METRIC).count())
                .isEqualTo(requests - 1);
        assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.RETRY_DROPPED_METRIC).count()).isZero();
        // 합친 재시도는 한 번만 실행된다: 첫 확인 requests번 + 재시도 1번
        verify(reader, timeout(3_000).times(requests + 1)).findChatAllowedUserIds(List.of(HOST_ID));
        verify(reader, after(300).times(requests + 1)).findChatAllowedUserIds(List.of(HOST_ID));
    }

    @Test
    void 재시도를_기다리는_회원이_상한이면_예약하지_않고_세며_주기_재검사가_그_회원을_다시_본다() {
        // 재시도는 한 번만 해서, 예약한 회원의 재시도가 끝난 뒤에는 남은 예약이 없게 한다
        DefaultChatConnectionRecheckService limited = service(2, 1, Duration.ofMillis(200), Duration.ofMillis(200));
        limited.setMaxPendingRetryUsers(2);
        when(connections.connectedAmong(any())).thenAnswer(invocation -> Set.copyOf(invocation.getArgument(0)));
        when(reader.findChatAllowedUserIds(any())).thenThrow(new QueryTimeoutException("DB 지연"));

        limited.recheckUser(HOST_ID);
        limited.recheckUser(GUEST_ID);
        limited.recheckUser(ADMIN_ID);

        assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.RETRY_DROPPED_METRIC).count())
                .isEqualTo(1);
        // 예약한 두 회원만 다시 확인하고, 상한에서 빠진 회원은 다시 확인하지 않는다
        await().atMost(Duration.ofSeconds(3)).until(() -> meterRegistry
                .counter(DefaultChatConnectionRecheckService.RETRY_EXHAUSTED_METRIC).count() == 2);
        verify(reader, times(2)).findChatAllowedUserIds(List.of(HOST_ID));
        verify(reader, times(2)).findChatAllowedUserIds(List.of(GUEST_ID));
        verify(reader, times(1)).findChatAllowedUserIds(List.of(ADMIN_ID));

        // 조회가 회복되면 주기 재검사가 예약에서 빠진 회원도 다시 본다
        reset(reader);
        when(connections.connectedUserIds()).thenReturn(new LinkedHashSet<>(List.of(HOST_ID, GUEST_ID, ADMIN_ID)));
        when(reader.findChatAllowedUserIds(List.of(HOST_ID, GUEST_ID))).thenReturn(Set.of(HOST_ID, GUEST_ID));
        when(reader.findChatAllowedUserIds(List.of(ADMIN_ID))).thenReturn(Set.of());

        limited.recheckAllConnected();

        verify(connections).closeNotAllowed(ADMIN_ID);
    }

    @Test
    void 예약한_재시도가_실행된_뒤의_새_실패는_합치지_않고_다시_예약한다() {
        DefaultChatConnectionRecheckService retrying = service(500, 3, Duration.ofMillis(50), Duration.ofMillis(50));
        when(connections.connectedAmong(List.of(HOST_ID))).thenReturn(Set.of(HOST_ID));
        when(reader.findChatAllowedUserIds(List.of(HOST_ID)))
                .thenThrow(new QueryTimeoutException("DB 지연"))
                .thenReturn(Set.of(HOST_ID))
                .thenThrow(new QueryTimeoutException("DB 지연"))
                .thenReturn(Set.of());
        retrying.recheckUser(HOST_ID);
        // 재시도가 실행돼 자격을 확인했다(닫지 않음)
        verify(reader, timeout(2_000).times(2)).findChatAllowedUserIds(List.of(HOST_ID));
        verify(connections, never()).closeNotAllowed(any());

        retrying.recheckUser(HOST_ID);

        // 기다리는 회원에서 빠졌으므로 새 실패는 새 재시도로 예약되고, 그 재시도가 닫는다
        verify(connections, timeout(2_000)).closeNotAllowed(HOST_ID);
        assertThat(meterRegistry.counter(DefaultChatConnectionRecheckService.RETRY_COALESCED_METRIC).count())
                .isZero();
    }

    private DefaultChatConnectionRecheckService service(int batchSize, int maxRetries, Duration initialDelay,
            Duration maxDelay) {
        DefaultChatConnectionRecheckService created = new DefaultChatConnectionRecheckService(connections, reader,
                meterRegistry, batchSize, maxRetries, initialDelay, maxDelay);
        this.created.add(created);
        return created;
    }
}
