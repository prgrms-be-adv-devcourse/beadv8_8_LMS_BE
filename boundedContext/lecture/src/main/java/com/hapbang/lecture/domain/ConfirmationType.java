package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 구매 확정 방식. 직접 확정, 첫 재생(재생 동의), 구매일 다음 날부터 7일 경과 중 먼저 온 것.
 */
@Schema(description = """
        구매 확정 방식
        - `DIRECT`: 구매자가 직접 확정
        - `FIRST_PLAY`: 처음 볼 때 재생 동의로 확정
        - `AUTO`: 구매일 다음 날부터 7일이 지나 자동 확정""")
public enum ConfirmationType {
    /** 구매자의 확정 요청 */
    DIRECT,
    /** 영상·자료를 처음 보기 전 재생 동의(D7) */
    FIRST_PLAY,
    /** {@code autoConfirmAt} 경과 */
    AUTO
}
