package com.hapbang.coupon.domain;

/**
 * DLT 격리 기록의 상태. PENDING에서 시작해 재발행(REPLAYING → REPLAYED)이나 영구 거절(REJECTED)로 끝난다.
 * <ul>
 *     <li>REPLAYING은 관리자 재발행이 전송 <b>전에</b> 선점한 상태다(선점 시각을 남긴다). broker ACK를 받으면 REPLAYED, 전송이 실패하면
 *     PENDING으로 돌아간다. 프로세스가 그 사이에 죽어 REPLAYING이 남으면, 선점 lease가 지난 뒤 관리자 재발행·영구 거절이 PENDING처럼 다시
 *     선점할 수 있다({@code CouponDltLog.canClaim}). 전송 결과가 확정되기 전이므로 지표·재발행 배치 제외·복구 집계는 PENDING과 함께
 *     미처리로 센다({@link #isUnresolved}).</li>
 *     <li>REPLAYED는 원 토픽에 다시 보냈다는 뜻이지 처리 완료가 아니다. 결과는 발급 요청의 DB 결과로 확인한다. 다시 실패하면 새 PENDING 기록이
 *     생긴다(선점이 전송보다 먼저 커밋되므로 재발행 메시지가 바로 다시 격리돼도 새 PENDING이 막히지 않는다).</li>
 *     <li>REJECTED는 DB에 거절을 확정한 영구 거절이다. Redis 수량 보상은 그 뒤에 하고 결과를 따로 남긴다.</li>
 * </ul>
 * lease에 따른 재선점은 시각이 필요하므로 {@link #canTransitTo} 밖에서 {@code CouponDltLog}가 판단한다.
 */
public enum CouponDltStatus {
    /** 격리됨. 관리자 판단을 기다린다(재발행 배치도 다시 보내지 않는다) */
    PENDING,
    /** 관리자 재발행이 전송 전에 선점했다(전송 결과를 기다린다) */
    REPLAYING,
    /** 관리자가 원 토픽에 다시 보냈다 */
    REPLAYED,
    /** 관리자가 영구 거절했다 */
    REJECTED;

    /**
     * 이 상태에서 {@code next}로 바꿀 수 있는가(lease 판단 전). PENDING → REPLAYING·REJECTED, REPLAYING → REPLAYED(ACK)·PENDING(전송
     * 실패)이다. lease가 지난 REPLAYING의 재선점(REPLAYING·REJECTED)은 {@code CouponDltLog}가 따로 허용한다.
     */
    public boolean canTransitTo(CouponDltStatus next) {
        return switch (this) {
            case PENDING -> next == REPLAYING || next == REJECTED;
            case REPLAYING -> next == REPLAYED || next == PENDING;
            case REPLAYED, REJECTED -> false;
        };
    }

    /** 아직 처리되지 않았는가(PENDING·REPLAYING) */
    public boolean isUnresolved() {
        return this == PENDING || this == REPLAYING;
    }
}
