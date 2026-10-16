package com.hapbang.appearance.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 게스트가 출연으로 받을 수익의 지급 상태 (#301). Billing 공통 계약({@code EarningStatus})에서 출연이 쓰는 값만 둔다.
 * 출연은 확인하는 순간 지급을 실행하므로 "지급 시각이 정해졌지만 실행 전"인 {@code SCHEDULED}가 없다.
 * <ul>
 *     <li>CONFIRMATION_PENDING: 성립, 방송 뒤 호스트 확인(또는 7일 뒤 자동 확인)을 기다림</li>
 *     <li>ON_HOLD: 게스트 출연 취소 요청을 호스트가 처리 중</li>
 *     <li>PROCESSING: 지급·위약금 작업이 처리 중 (재시도 포함)</li>
 *     <li>FAILED: 지급·위약금 작업이 격리됨(DEAD), 또는 있어야 할 작업이 없음. 고객센터 확인 필요</li>
 *     <li>COMPLETED: 지급·위약금 완료</li>
 *     <li>CANCELED: 받을 수익 없음 (불참, 합의 취소)</li>
 * </ul>
 */
public enum AppearanceEarningStatus {
    CONFIRMATION_PENDING,
    ON_HOLD,
    PROCESSING,
    FAILED,
    COMPLETED,
    CANCELED;

    /** 아직 받지 못한 수익 (요약·기본 목록 대상) */
    public static final Set<AppearanceEarningStatus> UNPAID = EnumSet.of(CONFIRMATION_PENDING, ON_HOLD, PROCESSING, FAILED);
}
