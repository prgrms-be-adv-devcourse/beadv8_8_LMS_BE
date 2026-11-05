package com.hapbang.chat.app;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import com.hapbang.chat.app.dto.ReadChangedFanout;

import lombok.extern.slf4j.Slf4j;

/**
 * 읽음 위치·참여 변경이 안 읽은 사람 수를 바꿨을 수 있음을 알린다(설계 Q36). 변경 트랜잭션 안에서 부르면 커밋된 뒤에만 내고
 * (롤백되면 내지 않는다), 트랜잭션 밖이면 바로 낸다.
 * <ul>
 *     <li>발행은 상한이 있는 전용 실행기에서 한다. 커밋한 스레드(요청·Kafka 소비 스레드)는 작업을 넣기만 하므로, Redis가 느려도
 *     그 스레드와 DB 연결을 붙잡지 않는다(커밋 뒤 콜백은 연결 반환 전에 돈다).</li>
 *     <li>outbox를 쓰지 않는 최선 노력이다. 대기열이 가득 차거나 발행이 실패하면 버리고 기록만 남긴다. 변경은 실패시키지 않는다.
 *     잃은 갱신은 FE가 REST 이력으로 보정한다.</li>
 *     <li>실시간 전달을 끈 앱(발행 빈이 없음)에서는 아무것도 하지 않는다.</li>
 *     <li>실행기는 Boot가 주입하는 builder로 만든다. 그래야 앱의 {@code traceContextTaskDecorator}(#630)가 붙어 발행 스레드의
 *     로그에 신호를 낸 요청·소비의 {@code traceId}가 찍힌다(docs/dev/logging.md "스레드 경계", #582).</li>
 * </ul>
 */
@Slf4j
@Component
public class ChatReadCountSignals implements DisposableBean {

    private final ObjectProvider<ChatReadCountPublisher> publisher;
    private final ThreadPoolTaskExecutor executor;

    /**
     * 발행 스레드 수(1)·대기열 상한(1000)은 미확정 운영 수치(Q12)다. 기본값은 코드가 원본이고 같은 키의 yaml·환경 변수가 덮는다.
     */
    public ChatReadCountSignals(ObjectProvider<ChatReadCountPublisher> publisher,
            ThreadPoolTaskExecutorBuilder executorBuilder,
            @Value("${hapbang.chat.realtime.read-counts.publish-workers:1}") int workers,
            @Value("${hapbang.chat.realtime.read-counts.publish-queue-capacity:1000}") int queueCapacity) {
        Assert.isTrue(workers > 0, "hapbang.chat.realtime.read-counts.publish-workers는 1 이상이어야 합니다.");
        Assert.isTrue(queueCapacity > 0, "hapbang.chat.realtime.read-counts.publish-queue-capacity는 1 이상이어야 합니다.");
        this.publisher = publisher;
        // builder의 기본값(spring.task.execution.*) 중 바꾸면 동작이 달라지는 값은 모두 여기서 정한다.
        // 빈이 아니라 Spring이 초기화하지 않으므로 직접 initialize한다
        this.executor = executorBuilder
                .threadNamePrefix("chat-read-count-signal-")
                .corePoolSize(workers)
                .maxPoolSize(workers)
                .queueCapacity(queueCapacity)
                .allowCoreThreadTimeOut(false)
                .build();
        this.executor.initialize();
    }

    /** 읽음 위치가 올랐다. 참여는 그대로라 방 참여 캐시를 다시 읽게 하지 않는다 */
    public void readChanged(Long roomId) {
        signal(new ReadChangedFanout(roomId, 0));
    }

    /**
     * 참여(초대·강퇴·나가기)나 방 상태가 바뀌었다.
     *
     * @param recipientSetVersion 그 변경이 만든 수신자 집합 버전. 참여가 그대로면(폐쇄·호스트 자격 상실) 0
     */
    public void membershipChanged(Long roomId, int recipientSetVersion) {
        signal(new ReadChangedFanout(roomId, recipientSetVersion));
    }

    /** 종료할 때 새 발행을 받지 않는다. 남은 신호는 버린다(최선 노력) */
    @Override
    public void destroy() {
        executor.shutdown();
    }

    private void signal(ReadChangedFanout fanout) {
        if (publisher.getIfAvailable() == null || fanout.roomId() == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(fanout);
                }
            });
            return;
        }
        submit(fanout);
    }

    /** 실행기에 넣기만 한다. 대기열이 가득 찼거나 종료 중이면 버린다 */
    private void submit(ReadChangedFanout fanout) {
        try {
            executor.execute(() -> publish(fanout));
        } catch (TaskRejectedException exception) {
            log.warn("안 읽은 사람 수 변경 신호 대기열이 가득 차 버림 roomId={}", fanout.roomId());
        }
    }

    private void publish(ReadChangedFanout fanout) {
        ChatReadCountPublisher target = publisher.getIfAvailable();
        if (target == null) {
            return;
        }
        try {
            target.publish(fanout);
        } catch (RuntimeException exception) {
            log.warn("안 읽은 사람 수 변경 신호를 내지 못함 roomId={}", fanout.roomId(), exception);
        }
    }
}
