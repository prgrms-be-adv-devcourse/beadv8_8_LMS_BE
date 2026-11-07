package com.hapbang.coupon.domain;

import java.util.Arrays;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 발급 접수 응답 코드. 발급 접수 Lua({@code issue.lua})의 반환값을 해석한다. 접수(ACCEPTED)는 완료가 아니다.
 */
@Getter
@RequiredArgsConstructor
public enum IssueResponseCode {

    /** 새로 접수했다 */
    ACCEPTED(1),
    /** 같은 요청 ID가 이미 접수돼 있다(재전송). 기록을 바꾸지 않았다 */
    ALREADY_ACCEPTED(2),
    /** 남은 수량이 없다 */
    SOLD_OUT(0),
    /** 이 회원은 이 쿠폰을 이미 받았다(다른 요청 ID) */
    ALREADY_ISSUED(-1),
    /** 발급이 열려 있지 않다(준비 전, 중단, 종료, epoch 불일치) */
    NOT_OPEN(-2),
    /** 발급 기간이 아니다 */
    NOT_IN_PERIOD(-3),
    /** 같은 요청 ID를 다른 회원이 이미 썼다 */
    REQUEST_ID_CONFLICT(-4),
    /** 스크립트 오류(키 타입·인수 오류, Stream 기록 실패 뒤 되돌림). 접수하지 않았다 */
    ERROR(-9);

    private final long luaValue;

    /** Lua 반환값을 응답 코드로 바꾼다. 알 수 없는 값은 {@link CouponDomainError#UNKNOWN_LUA_RESULT}다 */
    public static IssueResponseCode fromLua(long luaValue) {
        return Arrays.stream(values())
                .filter(code -> code.luaValue == luaValue)
                .findFirst()
                .orElseThrow(() -> new CouponDomainException(CouponDomainError.UNKNOWN_LUA_RESULT,
                        "발급 접수 결과를 해석하지 못했습니다: " + luaValue));
    }

    /** 접수 기록이 있는가(새 접수 또는 같은 요청 재응답) */
    public boolean isAccepted() {
        return this == ACCEPTED || this == ALREADY_ACCEPTED;
    }
}
