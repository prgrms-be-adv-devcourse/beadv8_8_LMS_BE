package com.hapbang.coupon.domain;

/**
 * 복구 5단계(재개) 전 수량 대사: {@code 총수량 = DB 확정 발급 수 + Redis 가용 재고 + 진행 중}이고 진행 중이 0이어야 재개한다.
 * 재고가 없거나(유실) 음수면 맞지 않는다. 같은 기준 시점에서 읽어야 하므로 접수가 막힌 동안(재구축 뒤 재개 전)에만 쓴다.
 *
 * @param redisStock Redis 가용 재고. 없으면(유실) null
 * @param inProgress requests에 있고 DB에 결과가 없는 요청 수
 */
public record RecoveryQuantityCheck(int quantity, int dbIssued, Long redisStock, long inProgress) {

    public boolean matches() {
        return redisStock != null && redisStock >= 0 && inProgress == 0
                && (long) quantity == (long) dbIssued + redisStock + inProgress;
    }

    /** recovery_run의 재개 근거에 남기는 문자열 */
    public String describe() {
        return "quantity=" + quantity + ",dbIssued=" + dbIssued + ",redisStock=" + redisStock + ",inProgress=" + inProgress;
    }
}
