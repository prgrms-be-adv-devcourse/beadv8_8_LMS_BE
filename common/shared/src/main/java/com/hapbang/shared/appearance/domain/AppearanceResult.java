package com.hapbang.shared.appearance.domain;

/**
 * 출연의 공개 결과 (#713). 출연 내부 상태 6개를 다른 모듈이 해석하지 않도록 출연이 줄여서 알려 준다.
 * 출연 변경 이벤트({@code AppearanceChangedEvent})와 출연 동기화 내부 API의 계약이다. 상수 이름은 바꾸지 않는다.
 */
public enum AppearanceResult {
    /** 성립해 방송을 기다린다 (출연 취소 요청 중 포함) */
    SCHEDULED,
    /** 출연이 확인됐다 (호스트 확인 또는 D+7 자동 확인) */
    COMPLETED,
    /** 취소됐다 (호스트 일방 취소 또는 합의 취소) */
    CANCELED,
    /** 게스트가 출연하지 않았다 (호스트의 불참 처리) */
    NO_SHOW
}
