package com.hapbang.chat.in.support;

import java.time.Duration;
import java.util.List;

import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import com.hapbang.chat.app.ChatDeliveryAvailability;
import com.hapbang.chat.in.redis.ChatRealtimeRedisListener;
import com.hapbang.chat.in.stream.ChatDeliveryPipelineRunner;
import com.hapbang.chat.out.redis.ChatRedisKeys;

import lombok.extern.slf4j.Slf4j;

/**
 * E2E 테스트 메서드마다 전달 파이프라인을 깨끗한 상태로 시작한다.
 * <p>
 * DB 정리({@code DatabaseCleanUpListener})는 테이블만 비운다. 메서드가 XADD 뒤 소비 전에 끝나면 Stream·PEL과 돌고 있는 worker가
 * 남아, 다음 메서드가 그 작업을 읽고 원본이 없다며 새 DB에 DEAD 체크포인트를 남긴다. 이미 발행된 작업도 구독자의 처리 실행기에
 * 남아 있다가 늦게 전달되면, 다음 메서드가 같은 회원으로 연 연결에 들어갈 수 있다. 그래서
 * <ol>
 *     <li>메서드가 끝나면 DB 정리 전에(이 리스너의 순서가 더 커서 afterTestMethod가 먼저 돈다) 파이프라인을 멈추고, 구독자의
 *     새 payload 처리를 멈춘 뒤 처리 중인 작업이 끝나기를 기다린다(운영 종료와 같은 {@code pause}·{@code awaitIdle}).</li>
 *     <li>이 컨텍스트의 namespace에 있는 전달·제어 Stream 키만 지운다(그룹·PEL도 함께 지워진다). FLUSHALL 등 전역 정리는 하지 않는다.</li>
 *     <li>다음 메서드 시작 전에 전달 가용성 상태(Redis 멈춤·자기 구독 단절·메모리 상한 재확인)를 초기화하고, 구독자 처리와
 *     파이프라인을 다시 시작한다. 시작할 때 그룹을 다시 만든다. 앞 실행의 worker가 아직 끝나지 않아 시작을 거절하면 끝날 때까지
 *     제한 시간 안에서 다시 시도한다.</li>
 * </ol>
 * 파이프라인·실시간 구독을 켜지 않은 컨텍스트에서는 해당 단계를 건너뛴다.
 */
@Slf4j
public class ChatPipelineIsolationListener extends AbstractTestExecutionListener {

    /** 파이프라인 작업이 끝나지 않아 정리를 건너뛰어야 함을 DB 정리 리스너에 알리는 테스트 컨텍스트 속성 */
    public static final String PIPELINE_STILL_RUNNING = ChatPipelineIsolationListener.class.getName() + ".stillRunning";

    /** {@code DatabaseCleanUpListener}(3000)보다 커서 afterTestMethod가 DB 정리보다 먼저 돈다 */
    @Override
    public int getOrder() {
        return 3_500;
    }


    /** 앞 실행의 worker가 끝나 파이프라인을 다시 시작할 수 있을 때까지 기다리는 상한 */
    private static final Duration RESTART_TIMEOUT = Duration.ofSeconds(30);

    @Override
    public void beforeTestMethod(TestContext testContext) throws InterruptedException {
        ApplicationContext context = testContext.getApplicationContext();
        ChatDeliveryAvailability availability = context.getBeanProvider(ChatDeliveryAvailability.class)
                .getIfAvailable();
        if (availability != null) {
            availability.reset();
        }
        ChatRealtimeRedisListener realtimeListener = realtimeListener(context);
        if (realtimeListener != null) {
            realtimeListener.resume();
        }
        ChatDeliveryPipelineRunner runner = runner(context);
        if (runner != null && !runner.isRunning()) {
            startWhenPreviousWorkersEnd(runner);
        }
    }

    private static void startWhenPreviousWorkersEnd(ChatDeliveryPipelineRunner runner) throws InterruptedException {
        long deadline = System.nanoTime() + RESTART_TIMEOUT.toNanos();
        while (true) {
            try {
                runner.start();
                return;
            } catch (IllegalStateException previousStillRunning) {
                if (System.nanoTime() >= deadline) {
                    throw previousStillRunning;
                }
                Thread.sleep(50);
            }
        }
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        ApplicationContext context = testContext.getApplicationContext();
        ChatDeliveryPipelineRunner runner = runner(context);
        if (runner != null) {
            runner.stop();
            // stop()은 shutdown-await 안에 끝나지 않은 worker·주기 작업을 남기고 돌아올 수 있다. 정리 전에 모두 끝났는지 확인하고,
            // 끝나지 않으면 Stream·DB를 정리하지 않고(DB 정리 리스너에 알림) 컨텍스트를 닫으며 테스트를 실패시킨다
            if (!awaitFullyStopped(runner)) {
                testContext.setAttribute(PIPELINE_STILL_RUNNING, true);
                testContext.markApplicationContextDirty(null);
                throw new IllegalStateException("전달 파이프라인의 worker·주기 작업이 " + fullStopTimeout
                        + " 안에 끝나지 않아 Stream·DB를 정리하지 않습니다. 다음 테스트로 작업이 샐 수 있습니다.");
            }
        }
        ChatRealtimeRedisListener realtimeListener = realtimeListener(context);
        if (realtimeListener != null) {
            realtimeListener.pause();
            if (!realtimeListener.awaitIdle(drainTimeout)) {
                // 파이프라인이 끝나지 않은 경우와 같이 다룬다: Stream·DB를 정리하지 않고 컨텍스트를 닫으며 실패시킨다
                testContext.setAttribute(PIPELINE_STILL_RUNNING, true);
                testContext.markApplicationContextDirty(null);
                throw new IllegalStateException("실시간 처리 작업이 " + drainTimeout
                        + " 안에 끝나지 않아 Stream·DB를 정리하지 않습니다. 다음 테스트로 작업이 샐 수 있습니다.");
            }
        }
        if (runner == null) {
            return;
        }
        ChatRedisKeys keys = context.getBean(ChatRedisKeys.class);
        try {
            context.getBean(StringRedisTemplate.class).delete(List.of(keys.deliveryStream(), keys.controlStream()));
        } catch (RuntimeException exception) {
            // Redis가 멈춘 테스트(장애 시험)에서 정리 실패가 원래 실패를 가리지 않게 한다. 다음 컨텍스트는 다른 namespace를 쓴다
            log.warn("E2E 파이프라인 Stream 정리 실패 namespace={}", keys.deliveryStream(), exception);
        }
    }

    /** 파이프라인의 모든 worker·주기 작업이 끝나기를 기다리는 상한 */
    private final Duration fullStopTimeout;
    /** 구독자 처리 작업을 기다리는 상한. 넘으면 정리하지 않고 실패시켜 다음 메서드로 새는 것을 숨기지 않는다 */
    private final Duration drainTimeout;

    public ChatPipelineIsolationListener() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    /** 테스트용: 모든 작업이 끝나기를 기다리는 상한을 바꾼다 */
    ChatPipelineIsolationListener(Duration fullStopTimeout) {
        this(fullStopTimeout, Duration.ofSeconds(10));
    }

    /** 테스트용: 파이프라인 종료·구독자 비우기를 기다리는 상한을 바꾼다 */
    ChatPipelineIsolationListener(Duration fullStopTimeout, Duration drainTimeout) {
        this.fullStopTimeout = fullStopTimeout;
        this.drainTimeout = drainTimeout;
    }

    /** @return 제한 시간 안에 모든 worker·주기 작업이 끝났으면 true */
    private boolean awaitFullyStopped(ChatDeliveryPipelineRunner runner) {
        long deadline = System.nanoTime() + fullStopTimeout.toNanos();
        while (!runner.isFullyStopped()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static ChatRealtimeRedisListener realtimeListener(ApplicationContext context) {
        return context.getBeanProvider(ChatRealtimeRedisListener.class).getIfAvailable();
    }

    private static ChatDeliveryPipelineRunner runner(ApplicationContext context) {
        return context.getBeanProvider(ChatDeliveryPipelineRunner.class).getIfAvailable();
    }
}
