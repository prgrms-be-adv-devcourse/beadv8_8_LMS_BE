package com.hapbang.billing.app;

import java.time.Instant;
import java.util.List;

// 기간 안에 바뀐 보류 목록 한 페이지 (#577). 커서는 항상 이 페이지의 마지막 보류 위치(빈 페이지면 요청한 커서)이고,
// hasMore는 커서 뒤에 확정 대기 시간보다 전에 바뀐 보류가 더 있는지다
public record HoldStatePage(
        List<HoldState> items,
        Instant nextUpdatedAfter,
        long nextAfterId,
        boolean hasMore
) {

    public HoldStatePage {
        items = List.copyOf(items);
    }
}
