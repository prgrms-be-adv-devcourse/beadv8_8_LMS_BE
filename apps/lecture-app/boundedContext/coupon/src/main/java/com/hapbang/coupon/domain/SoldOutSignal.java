package com.hapbang.coupon.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 메모리에 잠깐 재사용하는 매진 표시(Redis sold_out)를 읽은 결과. 매진 뒤 몰리는 요청을 Redis 호출 없이 거절하는 데 쓴다.
 * 최종 판정은 발급 Lua이므로 매진이 아니라는 값은 Lua 호출을 막지 않는다. 매진이 풀리면(보상으로 재고 복구) TTL 안에서는 늦게 보인다.
 *
 * @param soldOut 읽었을 때 매진 표시가 있었다
 * @param readAt  읽은 시각
 */
public record SoldOutSignal(boolean soldOut, Instant readAt) {

    public SoldOutSignal {
        Objects.requireNonNull(readAt, "readAt");
    }

    /**
     * 아직 재사용할 수 있는가: {@code readAt ≤ now < readAt + ttl}. TTL이 0이면 언제나 다시 읽는다. 시계가 읽은 시각보다 앞으로 돌아갔으면
     * 다시 읽는다.
     */
    public boolean freshAt(Instant now, Duration ttl) {
        return !now.isBefore(readAt) && now.isBefore(readAt.plus(ttl));
    }
}
