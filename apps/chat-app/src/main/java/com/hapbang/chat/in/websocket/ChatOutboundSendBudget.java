package com.hapbang.chat.in.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 앱 전체 송신 예산(설계 4.6). STOMP {@code clientOutboundChannel}이 쓰는 상한 있는 실행기다. 넘치면 버린 프레임의 연결을
 * {@link ChatWebSocketSessionRegistry#OUTBOUND_OVERFLOW}로 닫는다. 넘침 처리는 {@link ChatChannelBudget}을 따른다.
 * 버린 프레임 수는 {@value #REJECTED_METRIC}으로 센다.
 */
@Component
public class ChatOutboundSendBudget extends ChatChannelBudget {

    /** 앱 전체 송신 대기열이 넘쳐 버린 프레임 수 */
    public static final String REJECTED_METRIC = "chat.websocket.outbound.rejected";

    /**
     * 송신 스레드 수(16)·대기열 상한(10000 프레임)·닫기 대기열 상한(1000)은 미확정 초기값(Q12)이다. 기본값은 이 코드가 원본이고
     * application.yaml에 두지 않는다. 같은 키의 yaml·환경 변수가 덮는다.
     */
    public ChatOutboundSendBudget(ChatWebSocketSessionRegistry chatWebSocketSessionRegistry,
            MeterRegistry meterRegistry,
            @Value("${hapbang.chat.realtime.outbound.workers:16}") int workers,
            @Value("${hapbang.chat.realtime.outbound.queue-capacity:10000}") int queueCapacity,
            @Value("${hapbang.chat.realtime.outbound.close-queue-capacity:1000}") int closeQueueCapacity) {
        super("outbound", chatWebSocketSessionRegistry, ChatWebSocketSessionRegistry.OUTBOUND_OVERFLOW,
                counter(meterRegistry, REJECTED_METRIC), workers, queueCapacity, closeQueueCapacity);
    }
}
