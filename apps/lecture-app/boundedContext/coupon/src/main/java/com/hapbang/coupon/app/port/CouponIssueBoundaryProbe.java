package com.hapbang.coupon.app.port;

import com.hapbang.coupon.domain.RecoveryOffsets;

/**
 * 복구 경계를 위한 Kafka 상태 조회. 발급 요청 토픽({@code coupon.events.v1})과 그 DLT의 partition별 끝 offset, 발급 소비 그룹이 커밋한
 * offset을 읽는다. 메시지를 읽거나 쓰지 않는다.
 * <p>
 * Kafka에 닿지 못하면 {@link CouponIssueBoundaryUnavailableException}을 던진다(0으로 간주하지 않는다).
 */
public interface CouponIssueBoundaryProbe {

    /** 발급 요청 토픽의 partition별 끝 offset. 토픽이 없으면 빈 묶음 */
    RecoveryOffsets eventEndOffsets();

    /** DLT 토픽의 partition별 끝 offset. 토픽이 없으면 빈 묶음 */
    RecoveryOffsets dltEndOffsets();

    /** 발급 소비 그룹이 발급 요청 토픽에 커밋한 partition별 offset(다음에 읽을 위치). 커밋이 없으면 빈 묶음 */
    RecoveryOffsets committedOffsets();
}
