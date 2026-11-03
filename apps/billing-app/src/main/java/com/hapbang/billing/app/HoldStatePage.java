package com.hapbang.billing.app;

import java.time.Instant;
import java.util.List;

// 기간 안에 바뀐 보류 목록 한 페이지 (#577). 다음 페이지가 없으면 nextUpdatedAfter·nextAfterId가 null이다
public record HoldStatePage(
        List<HoldState> items,
        Instant nextUpdatedAfter,
        Long nextAfterId
) {

    public HoldStatePage {
        items = List.copyOf(items);
    }
}
