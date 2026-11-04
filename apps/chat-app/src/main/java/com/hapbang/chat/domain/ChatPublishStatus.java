package com.hapbang.chat.domain;

/**
 * consumer group별 Pub/Sub 발행 체크포인트 상태. 사용자 수신 여부가 아니다.
 */
public enum ChatPublishStatus {

    /** 아직 발행을 확인하지 못했다. Redis 유실 재구성의 대상이다 */
    PENDING,

    /** 구독자 1명 이상에게 발행했다. Redis 유실만으로 다시 발행하지 않는다 */
    PUBLISHED,

    /** 발행 시도 상한 초과·해석할 수 없는 항목·원본 누락으로 격리했다. 운영자가 처리 세대를 올려야 다시 처리한다 */
    DEAD
}
