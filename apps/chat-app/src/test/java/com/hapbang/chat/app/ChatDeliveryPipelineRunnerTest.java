package com.hapbang.chat.app;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.hapbang.chat.fixture.ChatFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.chat.domain.ChatControlReason;
import com.hapbang.chat.domain.ChatPublishStatus;
import com.hapbang.chat.in.stream.ChatDeliveryPipelineRunner;

/**
 * 파이프라인과 배치 실행을 모두 켜면 relay·worker·회수가 스스로 돌아 메시지가 발행된다.
 */
@DirtiesContext
@TestPropertySource(properties = {
        "hapbang.scheduling.enabled=true",
        "hapbang.chat.delivery.relay.poll-interval=50ms",
        "hapbang.chat.delivery.reclaim.interval=100ms"
})
class ChatDeliveryPipelineRunnerTest extends ChatDeliveryTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void redisNamespace(DynamicPropertyRegistry registry) {
        uniqueNamespace(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ChatConnectionControl chatConnectionControl;

    @MockitoSpyBean
    private ChatDeliveryQueue spiedQueue;

    @MockitoSpyBean
    private ChatOutboxRelay spiedRelay;

    @Autowired
    private ChatDeliveryPipelineRunner runner;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 멈추면 실행 중인 주기 작업을 끊지 않고 끝날 때까지 기다린 뒤 돌아온다. 그래야 stop() 뒤에 relay 배치가 마저 XADD·DB 기록을
     * 하지 않는다(E2E 메서드 사이 Stream 정리, 앱 종료). 작업이 interrupt되면 대기 중 예외가 나 완료 표시가 남지 않는다.
     */
    @Test
    void 멈추면_실행_중인_relay_배치가_끝난_뒤에_돌아온다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicBoolean finished = new AtomicBoolean();
        doAnswer(invocation -> {
            if (!armed.compareAndSet(true, false)) {
                return invocation.callRealMethod();
            }
            started.countDown();
            Thread.sleep(500);
            Object result = invocation.callRealMethod();
            finished.set(true);
            return result;
        }).when(spiedRelay).relayBatch(any());
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            runner.stop();

            assertThat(finished).as("stop()이 돌아오기 전에 실행 중이던 relay 배치가 끝났다").isTrue();
        } finally {
            runner.start();
        }
    }

    @Test
    void 실행기가_적재부터_발행_ACK까지_처리한다() {
        assertThat(context.getBeansOfType(ChatDeliveryPipelineRunner.class)).hasSize(1);

        UUID deliveryId = send("안녕하세요");

        await().atMost(WAIT).until(() -> checkpoint(deliveryId).getStatus() == ChatPublishStatus.PUBLISHED);
        assertThat(fanout.messages()).extracting(payload -> payload.deliveryId()).contains(deliveryId);
        await().atMost(WAIT).until(() -> pendingCount(ChatJobStream.DELIVERY) == 0);
    }

    @Test
    void 구독이_끊긴_동안의_작업은_구독이_돌아오면_회수_루프가_발행한다() {
        fanout.subscribers(0);
        UUID deliveryId = send("구독 단절 중");
        await().atMost(WAIT).until(() -> Long.valueOf(0).equals(checkpoint(deliveryId).getLastSubscriberCount()));
        assertThat(checkpoint(deliveryId).getAttemptCount()).isZero();
        assertThat(checkpoint(deliveryId).getStatus()).isEqualTo(ChatPublishStatus.PENDING);

        fanout.subscribers(1);

        await().atMost(WAIT).until(() -> checkpoint(deliveryId).getStatus() == ChatPublishStatus.PUBLISHED);
        assertThat(fanout.messages()).extracting(payload -> payload.deliveryId()).contains(deliveryId);
        await().atMost(WAIT).until(() -> pendingCount(ChatJobStream.DELIVERY) == 0);
    }

    @Test
    void 연결_제어도_제어_worker가_발행한다() {
        transactionTemplate.executeWithoutResult(status ->
                chatConnectionControl.requestRecheck(GUEST_ID, 7L, ChatControlReason.MEMBER_MODIFIED));

        await().atMost(WAIT).until(() -> fanout.controls().stream()
                .anyMatch(control -> control.userId().equals(GUEST_ID) && Long.valueOf(7L)
                        .equals(control.memberVersion())));
        await().atMost(WAIT).until(() -> pendingCount(ChatJobStream.CONTROL) == 0);
    }

    @Test
    void 전달_relay와_회수가_멈춰도_제어_relay와_회수는_따로_진행한다() throws Exception {
        assertThat(context.getBean(ChatDeliveryPipelineRunner.class).isRunning()).isTrue();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deliveryRelayBlocked = new CountDownLatch(1);
        try {
            // 1) 전달 발행이 멈춘다: worker와, 일정 시간 뒤 그 항목을 회수한 전달 회수 작업이 모두 발행에서 멈춘다
            fanout.blockMessagesUntil(release);
            send("멈출 메시지");
            await().atMost(WAIT).until(() -> fanout.blockedMessagePublishes() >= 2);
            // 2) 전달 적재도 멈춘다: 다음 전달 relay가 XADD에서 멈춘다
            doAnswer(invocation -> {
                deliveryRelayBlocked.countDown();
                release.await(30, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(spiedQueue).enqueue(eq(ChatJobStream.DELIVERY), anyMap());
            send("적재가 멈출 메시지");
            assertThat(deliveryRelayBlocked.await(5, TimeUnit.SECONDS)).isTrue();

            // 3) 제어 작업은 첫 발행이 실패해 제어 회수로만 완료된다. 제어 relay·회수가 전달 작업을 기다리지 않아야 한다
            fanout.failNextControls(1);
            transactionTemplate.executeWithoutResult(status ->
                    chatConnectionControl.requestRecheck(GUEST_ID, 9L, ChatControlReason.MEMBER_MODIFIED));

            await().atMost(WAIT).until(() -> fanout.controls().stream()
                    .anyMatch(control -> Long.valueOf(9L).equals(control.memberVersion())));
            await().atMost(WAIT).until(() -> pendingCount(ChatJobStream.CONTROL) == 0);
            // 전달 쪽은 그동안 계속 멈춰 있었다
            assertThat(fanout.blockedMessagePublishes()).isGreaterThanOrEqualTo(2);
            assertThat(fanout.messages()).isEmpty();
        } finally {
            release.countDown();
        }
    }
}
