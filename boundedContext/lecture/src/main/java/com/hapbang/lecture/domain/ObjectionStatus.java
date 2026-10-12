package com.hapbang.lecture.domain;

/**
 * 내용 다름 이의 제기 상태. 항목 상태와 따로 둔다.
 * <ul>
 *     <li>{@code NONE}: 이의 제기 없음</li>
 *     <li>{@code FILED}: 접수, 관리자 판단 대기. 지급을 멈춘다</li>
 *     <li>{@code ACCEPTED}: 인정. 항목은 반환된다</li>
 *     <li>{@code REJECTED}: 기각. 지급 시점이 되면 지급한다</li>
 * </ul>
 */
public enum ObjectionStatus {
    NONE,
    FILED,
    ACCEPTED,
    REJECTED
}
