package com.hapbang.lecture.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 내용 다름 이의 제기 상태. 항목 상태와 따로 둔다.
 */
@Schema(description = """
        내용 다름 이의 제기 상태
        - `NONE`: 이의 제기 없음
        - `FILED`: 접수되어 관리자 판단을 기다린다. 구매 취소할 수 없다
        - `ACCEPTED`: 인정. HP가 반환되었다
        - `REJECTED`: 기각""")
public enum ObjectionStatus {
    /** 이의 제기 없음 */
    NONE,
    /** 접수, 관리자 판단 대기. 지급과 구매 취소를 멈춘다 */
    FILED,
    /** 인정. 항목은 반환된다 */
    ACCEPTED,
    /** 기각. 지급 시점이 되면 지급한다 */
    REJECTED
}
