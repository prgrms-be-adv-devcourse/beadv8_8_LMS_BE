package com.hapbang.shared.appearance.domain;

/**
 * 모집 마감 방식 (#871). 모집 마감 이벤트({@code AppearanceRecruitClosedEvent})의 계약이다. 상수 이름은 바꾸지 않는다.
 * 알림은 기한 마감일 때만 보낸다(조기 마감은 호스트 본인의 행동).
 */
public enum AppearanceRecruitCloseType {
    /** 모집 마감 일시가 지나 배치가 마감했다 */
    DEADLINE,
    /** 호스트가 마감 일시 전에 조기 마감했다 */
    EARLY
}
