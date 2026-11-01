package com.hapbang.chat.domain;

/**
 * 전달·제어 outbox의 Stream 적재 상태. Pub/Sub 발행 결과({@link ChatPublishStatus})와 따로 관리한다.
 * {@code PENDING → PROCESSING → ENQUEUED}, 적재 시도 상한을 넘으면 {@code DEAD}다.
 */
public enum ChatOutboxStatus {

    /** 적재 대기. {@code nextAttemptAt}이 지나면 relay가 선점한다 */
    PENDING,

    /** relay가 처리권({@code claimToken}·{@code leaseUntil})을 가지고 적재 중이다. 처리권이 만료되면 회수한다 */
    PROCESSING,

    /** {@code XADD} 응답을 확인했다. 후속 발행 완료가 아니다 */
    ENQUEUED,

    /** 적재 시도 상한을 넘겨 격리했다. 자동으로 다시 적재하지 않는다 */
    DEAD
}
