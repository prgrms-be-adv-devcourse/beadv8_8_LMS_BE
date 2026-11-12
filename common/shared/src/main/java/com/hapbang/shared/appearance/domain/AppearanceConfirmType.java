package com.hapbang.shared.appearance.domain;

/**
 * 출연 확인 방식 (#871). 출연 확인 이벤트({@code AppearanceConfirmedEvent})의 계약이다. 상수 이름은 바꾸지 않는다.
 * 알림은 자동 확인일 때만 보낸다(호스트 확인은 호스트 본인의 행동).
 */
public enum AppearanceConfirmType {
    /** 호스트가 확인 기간 안에 출연을 확인했다 */
    HOST,
    /** 확인 기간이 끝날 때까지 호스트 응답이 없어 배치가 자동 확인했다 */
    AUTO
}
