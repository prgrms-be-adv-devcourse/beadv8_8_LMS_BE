package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** HP 작업 상태 */
@Getter
@RequiredArgsConstructor
public enum HpTaskStatus {
    PENDING("처리 대기 또는 재시도 중. 화면에는 \"처리 중\"으로 보인다"),
    DONE("Billing 처리 완료"),
    DEAD("최대 재시도를 넘겨 격리됨(DLQ). 사람이 원인을 확인해야 한다");

    private final String description;
}
