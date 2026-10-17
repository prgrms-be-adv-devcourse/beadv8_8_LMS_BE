package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 출연 확인 방식 (#282) */
@Getter
@RequiredArgsConstructor
public enum AppearanceConfirmType {
    HOST("호스트가 방송 뒤 확인 기간 안에 출연을 확인"),
    AUTO("확인 기간(방송 예정 일시 + 7일)까지 호스트 응답이 없어 배치가 자동 확인");

    private final String description;
}
