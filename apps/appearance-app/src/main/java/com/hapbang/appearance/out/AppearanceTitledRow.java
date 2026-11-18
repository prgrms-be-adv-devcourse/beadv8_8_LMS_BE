package com.hapbang.appearance.out;

import com.hapbang.appearance.domain.Appearance;

/**
 * 출연과 출처(요청서·모집글)의 현재 제목 한 줄 (#1002). 출연은 제목을 갖지 않아 조회할 때 출처를 조인해 함께 읽는다.
 *
 * @param title 출처 제목. 출처가 없으면 null
 */
public record AppearanceTitledRow(Appearance appearance, String title) {
}
