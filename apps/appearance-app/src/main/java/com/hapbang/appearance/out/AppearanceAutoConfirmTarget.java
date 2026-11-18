package com.hapbang.appearance.out;

/**
 * 자동 확인 대상 출연 한 건 (#1002). 확인 알림·지급 작업에 쓰는 출처 제목을 대상과 함께 읽는다.
 * 성립한 출연의 출처 제목은 배치가 처리할 때(요청서는 수락 뒤, 모집글은 마감 뒤) 더 바뀌지 않으므로 미리 읽어도 된다.
 *
 * @param title 출처 제목. 출처가 없으면 null
 */
public record AppearanceAutoConfirmTarget(Long appearanceId, String title) {
}
