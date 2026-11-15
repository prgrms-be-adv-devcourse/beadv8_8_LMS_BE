package com.hapbang.coupon.domain;

/**
 * 복구 경계까지 아직 DB 결과로 정리되지 않은 것(미처리). 하나라도 0이 아니면 재구축으로 넘어가지 않는다.
 *
 * @param pendingRequests Redis 접수 기록(requests)에 있는데 DB {@code coupon_issue_request}에 결과가 없는 요청 수
 * @param kafkaLag        복구 경계(발급 요청 토픽 끝 offset)까지 소비 그룹이 아직 커밋하지 않은 메시지 수. 재시도 중인 메시지도 여기 든다
 * @param dltUnresolved   이 쿠폰의 DLT 기록 중 아직 처리되지 않은 건수(PENDING과 재발행 선점 중인 REPLAYING)
 */
public record RecoveryUnresolved(long pendingRequests, long kafkaLag, long dltUnresolved) {

    public RecoveryUnresolved {
        if (pendingRequests < 0 || kafkaLag < 0 || dltUnresolved < 0) {
            throw new IllegalArgumentException("미처리 건수는 음수일 수 없다");
        }
    }

    public static RecoveryUnresolved none() {
        return new RecoveryUnresolved(0, 0, 0);
    }

    public long total() {
        return pendingRequests + kafkaLag + dltUnresolved;
    }

    public boolean isEmpty() {
        return total() == 0;
    }

    /** recovery_run에 남기는 문자열 */
    public String describe() {
        return "pendingRequests=" + pendingRequests + ",kafkaLag=" + kafkaLag + ",dltUnresolved=" + dltUnresolved;
    }
}
