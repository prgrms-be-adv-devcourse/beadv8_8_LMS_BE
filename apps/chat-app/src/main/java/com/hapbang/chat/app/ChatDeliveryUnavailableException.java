package com.hapbang.chat.app;

/**
 * 전달 인프라(Redis)에 연결할 수 없다: 연결 거부·연결/명령 시간 초과·연결 끊김·데이터 적재 중(LOADING).
 * 작업 자체의 문제가 아니므로 시도 횟수에 넣지 않고 격리(DEAD)로 몰지 않는다. 파이프라인은 멈췄다가 연결이 돌아오면 이어 간다.
 * Redis 구현(out/redis)이 연결 실패를 이 예외로 바꿔 던진다. OOM·WRONGTYPE 같은 명령 오류는 이 예외가 아니다.
 */
public class ChatDeliveryUnavailableException extends RuntimeException {

    public ChatDeliveryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
