package com.hapbang.chat.app;

/**
 * 작업 큐나 필수 consumer group이 없다(Redis 유실·초기화). 그룹을 다시 만들고 DB 미완료 작업으로 재구성한다.
 */
public class ChatQueueGroupMissingException extends RuntimeException {

    public ChatQueueGroupMissingException(ChatJobStream stream, Throwable cause) {
        super("작업 큐 또는 consumer group이 없습니다: " + stream, cause);
    }
}
