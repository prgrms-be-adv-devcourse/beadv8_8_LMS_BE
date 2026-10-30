package com.hapbang.chat.app;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;

/**
 * 테스트용 실시간 전파. Redis 없이 발행 결과를 메모리에 남기고, 실패·구독자 0명(자기 구독 단절)을 주입한다.
 * 운영 대체 경로로 쓰지 않는다. 테스트 설정에서만 빈으로 등록한다(컴포넌트 스캔 대상이 아니다).
 */
public class InMemoryRealtimeChatFanout implements RealtimeChatFanout {

    private final List<MessageFanout> messages = new CopyOnWriteArrayList<>();
    private final List<ControlFanout> controls = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final AtomicInteger publishCalls = new AtomicInteger();
    private final AtomicInteger controlFailuresLeft = new AtomicInteger();
    private volatile boolean interruptNext;
    private final AtomicInteger blockedMessagePublishes = new AtomicInteger();
    private volatile CountDownLatch messageGate;
    private volatile long subscribers = 1;

    @Override
    public long publishMessage(MessageFanout fanout) {
        if (interruptNext) {
            // 종료 중 발행하던 worker가 인터럽트된 상황(Lettuce가 인터럽트를 예외로 알린다)
            interruptNext = false;
            publishCalls.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("발행 중 인터럽트", new InterruptedException());
        }
        beforePublish();
        waitAtGate();
        if (subscribers > 0) {
            messages.add(fanout);
        }
        return subscribers;
    }

    @Override
    public long publishControl(ControlFanout fanout) {
        beforePublish();
        if (controlFailuresLeft.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
            throw new IllegalStateException("주입한 제어 발행 실패");
        }
        if (subscribers > 0) {
            controls.add(fanout);
        }
        return subscribers;
    }

    /** 다음 발행 {@code times}번을 예외로 실패시킨다 */
    public void failNext(int times) {
        failuresLeft.set(times);
    }

    /** 다음 메시지 발행을 인터럽트로 끝낸다(호출 스레드에 인터럽트를 건다) */
    public void interruptNextMessage() {
        interruptNext = true;
    }

    /** 다음 제어 발행 {@code times}번만 예외로 실패시킨다(메시지 발행은 그대로) */
    public void failNextControls(int times) {
        controlFailuresLeft.set(times);
    }

    /**
     * 메시지 발행을 {@code gate}가 열릴 때까지(최대 30초) 멈춘다. 느린·멈춘 Redis 발행을 흉내 낸다. 제어 발행은 멈추지 않는다.
     */
    public void blockMessagesUntil(CountDownLatch gate) {
        this.messageGate = gate;
    }

    /** 지금 멈춰 있는 메시지 발행 수 */
    public int blockedMessagePublishes() {
        return blockedMessagePublishes.get();
    }

    /** 발행 명령이 돌려줄 구독자 수. 0이면 자기 구독이 끊긴 상태다(받은 payload를 남기지 않는다) */
    public void subscribers(long count) {
        this.subscribers = count;
    }

    /** 구독자가 받은 메시지 payload */
    public List<MessageFanout> messages() {
        return List.copyOf(messages);
    }

    /** 구독자가 받은 제어 payload */
    public List<ControlFanout> controls() {
        return List.copyOf(controls);
    }

    /** 실패·구독자 0명을 포함한 발행 호출 수 */
    public int publishCalls() {
        return publishCalls.get();
    }

    public void reset() {
        messages.clear();
        controls.clear();
        failuresLeft.set(0);
        controlFailuresLeft.set(0);
        interruptNext = false;
        publishCalls.set(0);
        CountDownLatch gate = messageGate;
        messageGate = null;
        if (gate != null) {
            gate.countDown();
        }
        subscribers = 1;
    }

    private void waitAtGate() {
        CountDownLatch gate = messageGate;
        if (gate == null) {
            return;
        }
        blockedMessagePublishes.incrementAndGet();
        try {
            gate.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            blockedMessagePublishes.decrementAndGet();
        }
    }

    private void beforePublish() {
        publishCalls.incrementAndGet();
        if (failuresLeft.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
            throw new IllegalStateException("주입한 발행 실패");
        }
    }
}
