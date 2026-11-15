package com.hapbang.auxiliary.notification.app;

/** 관리자 Backfill 실행 상태(ND-092) */
public enum BackfillRunStatus {
    /** 실행 중. 페이지마다 갱신 시각이 바뀐다 */
    RUNNING,
    /** 끝까지 읽어 완료했다. 결과가 있다 */
    COMPLETED,
    /** 끝까지 하지 못했다(회원 API·DB 장애). 결과가 없고 완료가 아니다 */
    FAILED,
    /** RUNNING으로 남았지만 갱신이 오래 없어 실제 실행 여부를 알 수 없다(pod 재시작·Redis 쓰기 실패). 조회 때만 쓴다 */
    UNKNOWN
}
