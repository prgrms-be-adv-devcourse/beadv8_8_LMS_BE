package com.hapbang.chat.in.stream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import com.hapbang.chat.app.ChatDeliveryAvailability;
import com.hapbang.chat.app.ChatDeliveryProperties;
import com.hapbang.chat.app.ChatDeliveryQueue;
import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.app.ChatOutboxRelay;
import com.hapbang.chat.app.ChatStreamJobProcessor;
import com.hapbang.chat.app.ChatStreamReconstructor;
import com.hapbang.chat.app.ChatStreamTrimmer;
import com.hapbang.chat.global.config.ConditionalOnChatDeliveryPipeline;
import com.hapbang.chat.global.scheduling.ConditionalOnSchedulingEnabled;

import lombok.extern.slf4j.Slf4j;

/**
 * 전달 파이프라인 실행 진입점. 파이프라인과 배치 실행이 모두 켜졌을 때만 등록한다.
 * <ul>
 *     <li>새 작업 worker: 전달·제어 큐마다 정해진 수의 스레드가 블로킹 읽기를 반복한다. 이름은
 *     {@code <인스턴스>:<큐>:<번호>}다. 방마다 스레드·그룹을 만들지 않는다.</li>
 *     <li>주기 작업(relay·미확인 회수·DB 재구성·정리)은 큐마다 따로 등록한다. 전달·제어는 각자의 전용 스케줄러를 쓰고,
 *     스케줄러마다 주기 작업 수만큼 스레드를 둔다. 고정 지연 작업은 자기 자신과 겹치지 않으므로, 한 작업이 Redis·DB 지연으로
 *     멈춰도 같은 큐의 다른 작업이나 다른 큐의 작업이 스레드를 기다리지 않는다(설계 4.6: 제어·복구가 전송 폭주에 굶지 않게).
 *     다른 배치({@code taskScheduler})와도 실행 예산을 나눈다.</li>
 *     <li>처리는 모두 {@code app}의 서비스가 하고, 여기서는 반복·예외 격리만 한다.</li>
 *     <li>시작할 때마다 새 실행 세대를 만들고 worker는 자기 세대의 플래그만 본다. 멈출 때 세대를 끄고 worker를 인터럽트한 뒤
 *     {@code shutdown-await} 안에 실제로 끝났는지 확인한다. 끝나지 않은 worker·주기 작업이 남아 있으면 오류를 남기고, 그것들이
 *     끝날 때까지 다시 시작하지 않는다(옛 worker가 같은 consumer 이름으로 새 worker와 함께 도는 일을 막는다).</li>
 *     <li>Redis 연결 불가·자기 구독 단절 동안의 멈춤과 재확인은 {@link ChatDeliveryAvailability}가 판단한다. 멈춘 worker는
 *     인터럽트로 바로 깨어나 종료한다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnChatDeliveryPipeline
@ConditionalOnSchedulingEnabled
public class ChatDeliveryPipelineRunner implements SmartLifecycle {

    /** 재구성 요청(그룹 유실)을 확인하는 간격. 실제 대조 주기는 설정을 따른다 */
    private static final Duration RECONSTRUCT_CHECK_INTERVAL = Duration.ofSeconds(1);
    /** 큐마다 등록하는 주기 작업 수(relay·회수·재구성·정리). 큐별 스케줄러의 스레드 수다 */
    static final int PERIODIC_TASKS_PER_STREAM = 4;
    /** worker가 예상하지 못한 예외로 멈췄을 때 다시 읽기 전 대기 */
    private static final Duration WORKER_ERROR_PAUSE = Duration.ofSeconds(1);

    private final ChatOutboxRelay relay;
    private final ChatStreamJobProcessor processor;
    private final ChatStreamReconstructor reconstructor;
    private final ChatStreamTrimmer trimmer;
    private final ChatDeliveryQueue queue;
    private final ChatDeliveryProperties properties;
    private final ChatDeliveryAvailability availability;

    /** 지금 세대와, 아직 끝나지 않은 옛 세대의 worker */
    private final List<Worker> workers = new ArrayList<>();
    /** 지금 세대의 주기 작업 스케줄러 */
    private final Map<ChatJobStream, ThreadPoolTaskScheduler> schedulers = new EnumMap<>(ChatJobStream.class);
    /** 멈췄지만 실행 중인 작업이 끝나지 않은 옛 스케줄러 */
    private final List<ThreadPoolTaskScheduler> lingeringSchedulers = new ArrayList<>();
    /** 지금 실행 세대. 멈춰 있으면 null */
    private Generation generation;

    public ChatDeliveryPipelineRunner(ChatOutboxRelay relay, ChatStreamJobProcessor processor,
            ChatStreamReconstructor reconstructor, ChatStreamTrimmer trimmer, ChatDeliveryQueue queue,
            ChatDeliveryProperties properties, ChatDeliveryAvailability availability) {
        this.relay = relay;
        this.processor = processor;
        this.reconstructor = reconstructor;
        this.trimmer = trimmer;
        this.queue = queue;
        this.properties = properties;
        this.availability = availability;
    }

    /**
     * @throws IllegalStateException 이전 실행의 worker·주기 작업이 아직 끝나지 않았을 때
     */
    @Override
    public synchronized void start() {
        if (generation != null) {
            return;
        }
        reapFinished();
        if (!workers.isEmpty() || !lingeringSchedulers.isEmpty()) {
            throw new IllegalStateException("이전 실행이 아직 끝나지 않아 전달 파이프라인을 다시 시작할 수 없습니다: worker "
                    + workerNames() + ", 주기 작업 스케줄러 " + lingeringSchedulers.size() + "개");
        }
        availability.reset();
        Generation started = new Generation();
        generation = started;
        for (ChatJobStream stream : ChatJobStream.values()) {
            runSafely("consumer group 확인 " + stream, () -> queue.ensureGroup(stream));
        }
        String instanceId = properties.resolvedInstanceId();
        for (ChatJobStream stream : ChatJobStream.values()) {
            schedulePeriodicTasks(stream, instanceId);
        }

        startWorkers(started, instanceId, ChatJobStream.DELIVERY, properties.consumer().deliveryWorkers());
        startWorkers(started, instanceId, ChatJobStream.CONTROL, properties.consumer().controlWorkers());
        log.info("전달 파이프라인 시작 instanceId={}, deliveryWorkers={}, controlWorkers={}", instanceId,
                properties.consumer().deliveryWorkers(), properties.consumer().controlWorkers());
    }

    /**
     * 새 실행을 막고 실행 중인 작업이 끝나기를 {@code shutdown-await}만큼 기다린다. worker는 인터럽트해 블로킹 읽기·멈춤 대기에서
     * 깨운다. 주기 작업은 끊지 않는다(relay 배치가 XADD·DB 기록을 마치게 한다). 상한 안에 끝나지 않은 것은 남겨 두고 오류를 남긴다.
     */
    @Override
    public synchronized void stop() {
        Generation stopping = generation;
        if (stopping == null) {
            return;
        }
        generation = null;
        stopping.active = false;
        long deadline = System.nanoTime() + properties.shutdownAwait().toNanos();
        for (Worker worker : workers) {
            if (worker.generation() == stopping) {
                worker.thread().interrupt();
            }
        }
        for (ThreadPoolTaskScheduler scheduler : schedulers.values()) {
            scheduler.shutdown();
            lingeringSchedulers.add(scheduler);
        }
        schedulers.clear();
        for (Worker worker : workers) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                worker.thread().join(Duration.ofNanos(remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        reapFinished();
        if (workers.isEmpty() && lingeringSchedulers.isEmpty()) {
            log.info("전달 파이프라인 종료");
            return;
        }
        log.error("전달 파이프라인을 멈췄지만 {} 안에 끝나지 않은 작업이 있다. 끝날 때까지 다시 시작하지 않는다: worker {}, "
                + "주기 작업 스케줄러 {}개", properties.shutdownAwait(), workerNames(), lingeringSchedulers.size());
    }

    @Override
    public synchronized boolean isRunning() {
        return generation != null;
    }

    /** 살아 있는 worker 스레드 수(이전 세대 포함) */
    synchronized int aliveWorkerCount() {
        return (int) workers.stream().filter(worker -> worker.thread().isAlive()).count();
    }

    /** 큐 하나의 주기 작업을 그 큐 전용 스케줄러에 하나씩 등록한다 */
    private void schedulePeriodicTasks(ChatJobStream stream, String instanceId) {
        String name = stream.name().toLowerCase(Locale.ROOT);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(PERIODIC_TASKS_PER_STREAM);
        scheduler.setThreadNamePrefix("chat-" + name + "-periodic-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        scheduler.initialize();
        schedulers.put(stream, scheduler);

        int reclaimBudget = stream == ChatJobStream.DELIVERY
                ? properties.reclaim().deliveryBudget()
                : properties.reclaim().controlBudget();
        String reclaimer = consumerName(instanceId, stream, "reclaim");
        scheduler.scheduleWithFixedDelay(() -> runSafely("relay " + stream, () -> relay.relayBatch(stream)),
                properties.relay().pollInterval());
        scheduler.scheduleWithFixedDelay(
                () -> runSafely("회수 " + stream, () -> processor.reclaim(stream, reclaimer, reclaimBudget)),
                properties.reclaim().interval());
        scheduler.scheduleWithFixedDelay(
                () -> runSafely("재구성 " + stream, () -> reconstructor.reconstructIfDue(stream)),
                RECONSTRUCT_CHECK_INTERVAL);
        scheduler.scheduleWithFixedDelay(() -> runSafely("정리 " + stream, () -> trimmer.trim(stream)),
                properties.trim().interval());
    }

    private void startWorkers(Generation owner, String instanceId, ChatJobStream stream, int count) {
        for (int i = 1; i <= count; i++) {
            String consumer = consumerName(instanceId, stream, Integer.toString(i));
            Thread thread = new Thread(() -> pollLoop(owner, stream, consumer), "chat-stream-" + consumer);
            thread.setDaemon(true);
            workers.add(new Worker(thread, consumer, owner));
            thread.start();
        }
    }

    private void pollLoop(Generation owner, ChatJobStream stream, String consumer) {
        try {
            while (owner.active) {
                try {
                    processor.pollNew(stream, consumer);
                } catch (RuntimeException e) {
                    if (!owner.active) {
                        break;
                    }
                    log.error("새 작업 읽기 실패 stream={}, consumer={}", stream, consumer, e);
                    sleep(WORKER_ERROR_PAUSE);
                }
            }
        } finally {
            try {
                queue.releaseConsumer(consumer);
            } catch (RuntimeException e) {
                log.debug("consumer 자원 해제 실패 consumer={}", consumer, e);
            }
            log.debug("worker 종료 consumer={}", consumer);
        }
    }

    /** 끝난 worker와 종료된 옛 스케줄러를 목록에서 뺀다 */
    private void reapFinished() {
        workers.removeIf(worker -> !worker.thread().isAlive());
        lingeringSchedulers.removeIf(scheduler -> scheduler.getScheduledThreadPoolExecutor().isTerminated());
    }

    private List<String> workerNames() {
        return workers.stream().map(Worker::consumer).toList();
    }

    private static String consumerName(String instanceId, ChatJobStream stream, String workerId) {
        return instanceId + ":" + stream.name().toLowerCase(Locale.ROOT) + ":" + workerId;
    }

    private static void runSafely(String task, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("전달 파이프라인 작업 실패 task={}", task, e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 한 번의 시작~멈춤. worker는 자기 세대가 꺼지면 끝난다 */
    private static final class Generation {
        private volatile boolean active = true;
    }

    private record Worker(Thread thread, String consumer, Generation generation) {
    }
}
