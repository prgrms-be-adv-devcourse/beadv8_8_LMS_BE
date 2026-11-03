package com.hapbang.member.in;

/**
 * 다시 처리해도 같은 결과로 실패하는 이벤트(필수 값이 없거나 범위 밖) (#555).
 * 오류 처리기에 재시도하지 않는 예외로 등록해 재시도 없이 바로 DLT로 보낸다 ({@code MemberKafkaConfig}).
 */
public class RejectedEventException extends RuntimeException {

    public RejectedEventException(String message) {
        super(message);
    }
}
