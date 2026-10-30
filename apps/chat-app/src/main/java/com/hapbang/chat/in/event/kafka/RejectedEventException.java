package com.hapbang.chat.in.event.kafka;

/**
 * 다시 처리해도 같은 결과로 실패하는 이벤트(검증 실패, 신뢰 패키지 밖인데 처리기까지 온 타입). 오류 처리기에 재시도하지 않는 예외로
 * 등록해 블로킹 재시도 없이 바로 DLT로 보낸다({@link ChatKafkaErrorHandlerConfig}). 처리기가 없는 종류와 tombstone은 오류가 아니라
 * 리스너가 건너뛴다.
 */
public class RejectedEventException extends RuntimeException {

    public RejectedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
