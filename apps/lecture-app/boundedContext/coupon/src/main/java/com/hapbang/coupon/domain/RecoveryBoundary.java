package com.hapbang.coupon.domain;

import java.util.Objects;

/**
 * 복구 2단계(경계 확정)의 결과.
 *
 * @param republishedCount 같은 요청 ID·이전 epoch로 Kafka에 다시 보낸 요청 수
 * @param requestsLost     Redis 접수 기록을 믿을 수 없다(issue-control·재고가 없거나 Redis 프로세스가 바뀌었다). 그 사이 접수는 재발행할 수
 *                         없어 결과가 UNKNOWN으로 남을 수 있다
 * @param kafkaBoundary    재발행 뒤 발급 요청 토픽의 partition별 끝 offset({@code partition:offset}을 쉼표로 잇는다)
 * @param dltBoundary      같은 시점 DLT 토픽의 partition별 끝 offset
 */
public record RecoveryBoundary(int republishedCount, boolean requestsLost, String kafkaBoundary, String dltBoundary) {

    public RecoveryBoundary {
        if (republishedCount < 0) {
            throw new IllegalArgumentException("재발행 건수는 음수일 수 없다");
        }
        Objects.requireNonNull(kafkaBoundary, "kafkaBoundary");
        Objects.requireNonNull(dltBoundary, "dltBoundary");
    }
}
