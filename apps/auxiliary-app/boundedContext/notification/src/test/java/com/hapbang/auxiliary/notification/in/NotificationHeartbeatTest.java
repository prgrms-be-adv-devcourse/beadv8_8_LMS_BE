package com.hapbang.auxiliary.notification.in;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;

import com.hapbang.auxiliary.notification.app.NotificationConnectionTicketStore;
import com.hapbang.auxiliary.notification.app.NotificationUserSyncService;
import com.hapbang.auxiliary.notification.app.dto.ConnectionTicket;
import com.hapbang.auxiliary.notification.support.NotificationRealtimeTest;
import com.hapbang.auxiliary.notification.support.RawStomp;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

/**
 * 서버 STOMP heart-beat(ND-063, test-spec NT-RT-07 앱 쪽). 알림이 없어도 heart-beat가 오가 Gateway stream idle(5분)에 끊기지 않고,
 * 연결 만료(ND-056)에는 4401로 닫힌다. 운영 값은 25초이고 이 시험은 1초다.
 * <p>
 * STOMP 협상 규칙상 서버가 보내는 간격은 max(서버 보내기, 클라이언트 받기)이고 클라이언트 받기가 0이면 보내지 않는다. 서버가 받는 간격도
 * 같다(클라이언트 보내기가 0이면 확인하지 않는다).
 */
@NotificationRealtimeTest
@TestPropertySource(properties = "hapbang.notification.realtime.websocket.heartbeat=1s")
class NotificationHeartbeatTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(70_000);
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationUserSyncService userSync;

    @Autowired
    private NotificationConnectionTicketStore ticketStore;

    private final List<RawStomp> connections = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService clientBeats = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        clientBeats.shutdownNow();
        connections.forEach(RawStomp::closeQuietly);
    }

    @Test
    void CONNECTED에_서버_heart_beat_값을_싣는다() throws Exception {
        RawStomp stomp = connect(ticket(member(), Duration.ofMinutes(15)), 1000, 1000);

        String connected = stomp.awaitConnected(WAIT);

        assertThat(connected).isNotNull().contains("heart-beat:1000,1000");
    }

    @Test
    void 알림이_없어도_서버가_heart_beat를_보내고_연결이_유지된다() throws Exception {
        RawStomp stomp = connect(ticket(member(), Duration.ofMinutes(15)), 1000, 1000);
        assertThat(stomp.awaitConnected(WAIT)).isNotNull();
        beatEvery(stomp, Duration.ofMillis(300));

        Thread.sleep(4000);

        assertThat(stomp.drainHeartbeats()).isGreaterThanOrEqualTo(2);
        assertThat(stomp.isOpen()).isTrue();
    }

    @Test
    void 클라이언트가_받기를_0으로_두면_서버는_heart_beat를_보내지_않는다() throws Exception {
        RawStomp stomp = connect(ticket(member(), Duration.ofMinutes(15)), 0, 0);
        assertThat(stomp.awaitConnected(WAIT)).isNotNull();

        Thread.sleep(3000);

        assertThat(stomp.drainHeartbeats()).isZero();
        assertThat(stomp.isOpen()).isTrue();
    }

    @Test
    void 클라이언트_heart_beat가_끊기면_서버가_연결을_닫는다() throws Exception {
        RawStomp stomp = connect(ticket(member(), Duration.ofMinutes(15)), 1000, 1000);
        assertThat(stomp.awaitConnected(WAIT)).isNotNull();

        CloseStatus status = stomp.awaitClose(Duration.ofSeconds(10));

        assertThat(status).isNotNull();
        // 닫힘 콜백 안에서 깨어나므로 클라이언트 세션 상태가 아직 바뀌기 전일 수 있다(#849와 같은 이유). 바뀔 때까지 기다린다
        await().atMost(WAIT).until(() -> !stomp.isOpen());
    }

    @Test
    void heart_beat가_오가는_연결도_연결_만료에는_4401로_닫힌다() throws Exception {
        RawStomp stomp = connect(ticket(member(), Duration.ofSeconds(3)), 1000, 1000);
        assertThat(stomp.awaitConnected(WAIT)).isNotNull();
        beatEvery(stomp, Duration.ofMillis(300));

        CloseStatus status = stomp.awaitClose(Duration.ofSeconds(8));

        assertThat(status).isNotNull();
        assertThat(status.getCode()).isEqualTo(4401);
    }

    // ---------------------------------------------------------------------------------------------

    private long member() {
        long id = NEXT_ID.incrementAndGet();
        userSync.syncJoined(new UserDto(id, "회원" + id, Role.USER, UserStatus.ACTIVE, 0L));
        return id;
    }

    /** 발급 API를 거치지 않고 연결 만료를 정한 티켓을 저장소에 넣는다 */
    private String ticket(long userId, Duration lifetime) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        ticketStore.save(ticket, new ConnectionTicket(userId, Role.USER, now, now.plus(lifetime)), Duration.ofSeconds(30));
        return ticket;
    }

    private RawStomp connect(String ticket, long clientSend, long clientReceive) throws Exception {
        RawStomp stomp = RawStomp.connect(
                "ws://localhost:" + port + "/api/v1/notifications/ws?ticket=" + ticket, clientSend, clientReceive);
        connections.add(stomp);
        return stomp;
    }

    private void beatEvery(RawStomp stomp, Duration interval) {
        clientBeats.scheduleAtFixedRate(() -> {
            try {
                stomp.sendHeartbeat();
            } catch (Exception ignored) {
                // 닫힌 뒤에는 보내지 못한다
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }
}
