package com.hapbang.shared.appearance.domain;

/**
 * 게스트가 출연으로 받을 수익의 지급 상태. 출연 내부 수익 조회 API(#301)의 계약이다.
 * <p>
 * 강의의 수익 상태({@code LectureEarningStatus})와 상수 이름을 맞춘다 (docs/api/internal-earnings.md). 상수 이름은 HTTP 응답
 * 문자열이므로 바꾸지 않는다. 출연은 확인하는 순간 지급을 실행하므로 "지급 시각이 정해졌지만 실행 전"인 {@link #SCHEDULED}는
 * 나오지 않는다. 공통 필터로 받을 수 있게 값만 둔다 (그 상태만 고르면 빈 결과).
 */
public enum AppearanceEarningStatus {
    /** 성립. 방송 뒤 호스트 확인(또는 7일 뒤 자동 확인)을 기다린다 */
    CONFIRMATION_PENDING,
    /** 출연에는 없다 (공통 계약 값) */
    SCHEDULED,
    /** 게스트의 출연 취소 요청을 호스트가 처리 중이다 */
    ON_HOLD,
    /** 지급·위약금 작업이 처리 중이다 (일시 실패 뒤 재시도 포함) */
    PROCESSING,
    /** 지급·위약금 작업이 격리되었거나(DEAD) 있어야 할 작업이 없다. 관리자 조치가 필요하다 */
    FAILED,
    /** 지급·위약금 완료 */
    COMPLETED,
    /** 받을 수익이 없다 (불참, 합의 취소) */
    CANCELED;

    /** 아직 받지 못한 수익인지. 요약과 기본 목록은 이 상태만 본다 */
    public boolean isUnpaid() {
        return this != COMPLETED && this != CANCELED;
    }
}
