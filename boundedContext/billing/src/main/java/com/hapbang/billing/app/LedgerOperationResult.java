package com.hapbang.billing.app;

import java.util.List;

// 원장 기록 결과로, 기록(또는 재사용)한 원장 ID를 줄 순서대로 담고 이미 처리된 작업을 재사용했는지 표시한다.
public record LedgerOperationResult(String operationKey, boolean replayed, List<Long> ledgerIds) {

    // 원장 ID 목록을 변경할 수 없는 복사본으로 보관한다.
    public LedgerOperationResult {
        ledgerIds = List.copyOf(ledgerIds);
    }
}
