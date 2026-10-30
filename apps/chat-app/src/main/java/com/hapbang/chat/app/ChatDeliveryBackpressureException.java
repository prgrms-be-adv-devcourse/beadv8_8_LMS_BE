package com.hapbang.chat.app;

/**
 * 전달 큐(Redis)가 메모리 상한에 닿아 쓰기 명령을 거절했다({@code OOM} 응답, {@code noeviction}).
 * 작업 자체의 문제가 아니므로 시도 횟수에 넣지 않는다. 적재(relay·재적재)를 멈추고 미적재 작업을 DB outbox에 남겨 두며,
 * 쓰기가 다시 성공하면 이어 간다(설계 4.5 "용량 임계점에서는 XADD 유입을 제한하고 DB outbox에 미적재 작업을 남긴다").
 */
public class ChatDeliveryBackpressureException extends RuntimeException {

    public ChatDeliveryBackpressureException(String message, Throwable cause) {
        super(message, cause);
    }
}
