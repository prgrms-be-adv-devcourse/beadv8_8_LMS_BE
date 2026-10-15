package com.hapbang.appearance.domain;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 출연 모집 지원 상태.
 * <pre>
 * APPLIED ─호스트 수락─▶ SELECTED (출연 성립)
 *         ├호스트 거절─▶ NOT_SELECTED
 *         └게스트 취소─▶ WITHDRAWN
 * </pre>
 * 어느 쪽으로 끝나도 같은 게스트는 그 모집글에 다시 지원할 수 없다. 모집 마감 때 대기 중인 지원의 정리는 모집 마감 처리에서 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum AppearanceApplicationStatus {
    APPLIED("지원. 호스트의 수락·거절을 기다린다"),
    SELECTED("수락. 호스트가 수락해 출연이 성립했다"),
    NOT_SELECTED("미선정. 호스트가 거절했거나 모집이 마감됐다"),
    WITHDRAWN("지원 취소. 호스트 응답 전에 게스트가 취소했다");

    /** 지원자 수에 세는 상태: 게스트가 취소한 지원만 뺀 전체 지원 (ISSUE.md APR-27 A6) */
    public static final List<AppearanceApplicationStatus> COUNTED = List.of(APPLIED, SELECTED, NOT_SELECTED);

    private final String description;
}
