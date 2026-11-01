package com.hapbang.chat.domain;

/**
 * 발행 체크포인트를 격리({@link ChatPublishStatus#DEAD})한 원인.
 */
public enum ChatDeadReason {

    /** 연결된 Redis에서의 발행 실패가 시도 상한을 넘었다(구독자 0명·연결 불가는 인프라 장애라 세지 않는다) */
    PUBLISH_RETRY_EXHAUSTED,

    /** Redis 유실로 다시 적재한 횟수가 상한을 넘었는데 아직 발행하지 못했다 */
    REQUEUE_EXHAUSTED,

    /** Stream 항목에서 작업 ID를 읽을 수 없다 */
    MALFORMED_ENTRY,

    /** 작업 ID에 해당하는 outbox·체크포인트·메시지·수신자 집합이 없다 */
    SOURCE_MISSING
}
